package parking.forecast.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import parking.forecast.config.RefreshControlProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.RefreshStatus;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.SourceStateRepository;
import parking.forecast.service.RealtimeSourceRefreshService.RefreshExecutionResult;

@Service
public class SourceRefreshCoordinationService {

    private static final SourceSystem SOURCE_SYSTEM = SourceSystem.NTPC_OPEN_DATA;

    private final StringRedisTemplate redisTemplate;
    private final SourceStateRepository sourceStateRepository;
    private final RealtimeSourceRefreshService realtimeSourceRefreshService;
    private final RefreshControlProperties properties;
    private final Clock clock;
    private final Map<DatasetCode, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    @Autowired
    public SourceRefreshCoordinationService(
            StringRedisTemplate redisTemplate,
            SourceStateRepository sourceStateRepository,
            RealtimeSourceRefreshService realtimeSourceRefreshService,
            RefreshControlProperties properties
    ) {
        this(redisTemplate, sourceStateRepository, realtimeSourceRefreshService, properties, Clock.systemUTC());
    }

    SourceRefreshCoordinationService(
            StringRedisTemplate redisTemplate,
            SourceStateRepository sourceStateRepository,
            RealtimeSourceRefreshService realtimeSourceRefreshService,
            RefreshControlProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.sourceStateRepository = sourceStateRepository;
        this.realtimeSourceRefreshService = realtimeSourceRefreshService;
        this.properties = properties;
        this.clock = clock;
    }

    public SourceRefreshResult refresh(DatasetCode datasetCode, TriggerType triggerType) {
        Instant now = clock.instant();
        SourceRefreshResult cooldown = findCooldownResult(datasetCode, now);
        if (cooldown != null) {
            return cooldown;
        }

        LockHandle lock = acquireLock(datasetCode);
        if (!lock.acquired()) {
            return SourceRefreshResult.joined(datasetCode, latestSuccessfulAt(datasetCode), nextAllowedAt(datasetCode));
        }

        try {
            markRefreshing(datasetCode, now);
            RefreshExecutionResult executionResult = realtimeSourceRefreshService.refresh(datasetCode, triggerType);
            Instant completedAt = clock.instant();
            SourceRefreshResult result = executionResult.published()
                    ? SourceRefreshResult.refreshed(datasetCode, toOffset(completedAt), toOffset(completedAt.plus(properties.getSourceCooldown())), lock.redisAvailable())
                    : SourceRefreshResult.failed(datasetCode, latestSuccessfulAt(datasetCode), toOffset(completedAt.plus(properties.getSourceCooldown())), executionResult.message(), lock.redisAvailable());
            applyCooldown(
                    datasetCode,
                    completedAt,
                    executionResult.published() ? RefreshStatus.IDLE : RefreshStatus.FAILED,
                    executionResult.message(),
                    false
            );
            return result;
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            recordFailure(datasetCode, failedAt, exception.getMessage());
            return SourceRefreshResult.failed(
                    datasetCode,
                    latestSuccessfulAt(datasetCode),
                    toOffset(failedAt.plus(properties.getSourceCooldown())),
                    exception.getMessage(),
                    lock.redisAvailable()
            );
        } finally {
            releaseLock(lock);
        }
    }

    private SourceRefreshResult findCooldownResult(DatasetCode datasetCode, Instant now) {
        return sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .filter(state -> state.getNextAllowedRefreshAt() != null && state.getNextAllowedRefreshAt().isAfter(now))
                .map(state -> SourceRefreshResult.cooldown(
                        datasetCode,
                        toOffset(state.getLastSuccessfulFetchedAt()),
                        toOffset(state.getNextAllowedRefreshAt()),
                        state.getFailureReason()
                ))
                .orElse(null);
    }

    private LockHandle acquireLock(DatasetCode datasetCode) {
        String key = "parking:refresh-lock:%s:%s".formatted(SOURCE_SYSTEM, datasetCode);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, properties.getLockTtl());
            if (Boolean.TRUE.equals(acquired)) {
                return new LockHandle(datasetCode, key, token, true, true, null);
            }
            return new LockHandle(datasetCode, key, token, false, true, null);
        } catch (DataAccessException exception) {
            ReentrantLock localLock = localLocks.computeIfAbsent(datasetCode, ignored -> new ReentrantLock());
            boolean acquired = localLock.tryLock();
            return new LockHandle(datasetCode, key, token, acquired, false, localLock);
        }
    }

    private void releaseLock(LockHandle lock) {
        if (!lock.acquired()) {
            return;
        }
        if (lock.redisAvailable()) {
            try {
                String existingToken = redisTemplate.opsForValue().get(lock.key());
                if (lock.token().equals(existingToken)) {
                    redisTemplate.delete(lock.key());
                }
            } catch (DataAccessException ignored) {
                // 已完成刷新，刪除 Redis lock 失敗時讓 TTL 自動釋放。
            }
            return;
        }
        if (lock.localLock() != null && lock.localLock().isHeldByCurrentThread()) {
            lock.localLock().unlock();
        }
    }

    @Transactional
    protected void markRefreshing(DatasetCode datasetCode, Instant now) {
        SourceStateEntity state = sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .orElseGet(() -> new SourceStateEntity(SOURCE_SYSTEM, datasetCode, RefreshStatus.REFRESHING, now));
        state.setRefreshStatus(RefreshStatus.REFRESHING);
        state.setUpdatedAt(now);
        sourceStateRepository.save(state);
    }

    @Transactional
    protected void applyCooldown(
            DatasetCode datasetCode,
            Instant completedAt,
            RefreshStatus status,
            String message,
            boolean incrementFailureCount
    ) {
        SourceStateEntity state = sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .orElseGet(() -> new SourceStateEntity(SOURCE_SYSTEM, datasetCode, status, completedAt));
        state.setRefreshStatus(status);
        state.setNextAllowedRefreshAt(completedAt.plus(properties.getSourceCooldown()));
        state.setFailureReason(status == RefreshStatus.IDLE ? null : message);
        if (status == RefreshStatus.IDLE) {
            state.setConsecutiveFailureCount(0);
        } else if (incrementFailureCount) {
            state.setConsecutiveFailureCount(state.getConsecutiveFailureCount() + 1);
        }
        state.setUpdatedAt(completedAt);
        sourceStateRepository.save(state);
    }

    @Transactional
    protected void recordFailure(DatasetCode datasetCode, Instant failedAt, String reason) {
        SourceStateEntity state = sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .orElseGet(() -> new SourceStateEntity(SOURCE_SYSTEM, datasetCode, RefreshStatus.FAILED, failedAt));
        state.setRefreshStatus(RefreshStatus.FAILED);
        state.setFailureReason(reason == null ? "Source refresh failed" : reason);
        state.setConsecutiveFailureCount(state.getConsecutiveFailureCount() + 1);
        state.setNextAllowedRefreshAt(failedAt.plus(properties.getSourceCooldown()));
        state.setUpdatedAt(failedAt);
        sourceStateRepository.save(state);
    }

    private OffsetDateTime latestSuccessfulAt(DatasetCode datasetCode) {
        return sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .map(SourceStateEntity::getLastSuccessfulFetchedAt)
                .map(this::toOffset)
                .orElse(null);
    }

    private OffsetDateTime nextAllowedAt(DatasetCode datasetCode) {
        return sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, datasetCode)
                .map(SourceStateEntity::getNextAllowedRefreshAt)
                .map(this::toOffset)
                .orElse(null);
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record LockHandle(
            DatasetCode datasetCode,
            String key,
            String token,
            boolean acquired,
            boolean redisAvailable,
            ReentrantLock localLock
    ) {
    }

    public record SourceRefreshResult(
            DatasetCode datasetCode,
            String action,
            boolean refreshInProgress,
            OffsetDateTime lastSucceededAt,
            OffsetDateTime nextAllowedAt,
            String message,
            boolean redisAvailable
    ) {
        static SourceRefreshResult refreshed(
                DatasetCode datasetCode,
                OffsetDateTime lastSucceededAt,
                OffsetDateTime nextAllowedAt,
                boolean redisAvailable
        ) {
            return new SourceRefreshResult(
                    datasetCode,
                    redisAvailable ? "REFRESHED" : "REFRESHED_WITH_LOCAL_LOCK",
                    false,
                    lastSucceededAt,
                    nextAllowedAt,
                    redisAvailable ? "已完成來源刷新並發布最新快照。" : "Redis 不可用，已用本機鎖完成保守刷新。",
                    redisAvailable
            );
        }

        static SourceRefreshResult joined(
                DatasetCode datasetCode,
                OffsetDateTime lastSucceededAt,
                OffsetDateTime nextAllowedAt
        ) {
            return new SourceRefreshResult(
                    datasetCode,
                    "JOINED_RUNNING_REFRESH",
                    true,
                    lastSucceededAt,
                    nextAllowedAt,
                    "已有同來源刷新進行中，本次請求共用該輪刷新。",
                    true
            );
        }

        static SourceRefreshResult cooldown(
                DatasetCode datasetCode,
                OffsetDateTime lastSucceededAt,
                OffsetDateTime nextAllowedAt,
                String reason
        ) {
            String suffix = reason == null ? "" : " 最近一次原因：" + reason;
            return new SourceRefreshResult(
                    datasetCode,
                    "COOLDOWN",
                    false,
                    lastSucceededAt,
                    nextAllowedAt,
                    "來源仍在冷卻時間內，不重複呼叫官方 API。" + suffix,
                    true
            );
        }

        static SourceRefreshResult failed(
                DatasetCode datasetCode,
                OffsetDateTime lastSucceededAt,
                OffsetDateTime nextAllowedAt,
                String reason,
                boolean redisAvailable
        ) {
            return new SourceRefreshResult(
                    datasetCode,
                    redisAvailable ? "FAILED" : "FAILED_WITH_LOCAL_LOCK",
                    false,
                    lastSucceededAt,
                    nextAllowedAt,
                    reason == null ? "來源刷新失敗，保留既有已發布快照。" : "來源刷新失敗，保留既有已發布快照：" + reason,
                    redisAvailable
            );
        }
    }
}
