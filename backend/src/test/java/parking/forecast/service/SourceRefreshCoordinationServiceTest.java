package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import parking.forecast.config.RefreshControlProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.RefreshStatus;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.SourceStateRepository;
import parking.forecast.service.RealtimeSourceRefreshService.RefreshExecutionResult;

@ExtendWith(MockitoExtension.class)
class SourceRefreshCoordinationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SourceStateRepository sourceStateRepository;

    @Mock
    private RealtimeSourceRefreshService realtimeSourceRefreshService;

    private final AtomicReference<SourceStateEntity> sourceState = new AtomicReference<>();
    private final AtomicReference<String> redisLockToken = new AtomicReference<>();
    private SourceRefreshCoordinationService service;

    @BeforeEach
    void setUp() {
        RefreshControlProperties properties = new RefreshControlProperties();
        properties.setSourceCooldown(Duration.ofMinutes(5));
        properties.setLockTtl(Duration.ofSeconds(30));

        lenient().when(sourceStateRepository.findBySourceSystemAndDatasetCode(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(sourceState.get()));
        lenient().when(sourceStateRepository.save(any(SourceStateEntity.class)))
                .thenAnswer(invocation -> {
                    SourceStateEntity state = invocation.getArgument(0);
                    sourceState.set(state);
                    return state;
                });
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    redisLockToken.set(invocation.getArgument(1));
                    return true;
                });
        lenient().when(valueOperations.get(anyString())).thenAnswer(invocation -> redisLockToken.get());

        service = new SourceRefreshCoordinationService(
                redisTemplate,
                sourceStateRepository,
                realtimeSourceRefreshService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void skipsOfficialCallDuringSourceCooldown() {
        SourceStateEntity existing = new SourceStateEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                RefreshStatus.IDLE,
                NOW
        );
        existing.setLastSuccessfulFetchedAt(NOW.minusSeconds(60));
        existing.setNextAllowedRefreshAt(NOW.plusSeconds(60));
        sourceState.set(existing);

        var result = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);

        assertThat(result.action()).isEqualTo("COOLDOWN");
        assertThat(result.nextAllowedAt()).isEqualTo(NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
        verifyNoInteractions(redisTemplate, realtimeSourceRefreshService);
    }

    @Test
    void publishesOneRefreshAndStartsCooldown() {
        when(realtimeSourceRefreshService.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH))
                .thenReturn(new RefreshExecutionResult(true, 1L, 2L, 3, 4, "已發布快照"));

        var result = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);

        assertThat(result.action()).isEqualTo("REFRESHED");
        assertThat(result.nextAllowedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)).atOffset(ZoneOffset.UTC));
        assertThat(sourceState.get().getRefreshStatus()).isEqualTo(RefreshStatus.IDLE);
        assertThat(sourceState.get().getNextAllowedRefreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(realtimeSourceRefreshService).refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);
    }

    @Test
    void usesLocalLockWhenRedisIsUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new DataAccessResourceFailureException("Redis 不可用"));
        when(realtimeSourceRefreshService.refresh(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, TriggerType.MANUAL_REFRESH))
                .thenReturn(new RefreshExecutionResult(true, 1L, 2L, 1, 1, "已發布快照"));

        var result = service.refresh(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, TriggerType.MANUAL_REFRESH);

        assertThat(result.action()).isEqualTo("REFRESHED_WITH_LOCAL_LOCK");
        assertThat(result.redisAvailable()).isFalse();
        verify(realtimeSourceRefreshService).refresh(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, TriggerType.MANUAL_REFRESH);
    }

    @Test
    void joinsExistingLocalRefreshInsteadOfCallingSourceTwice() throws Exception {
        CountDownLatch sourceStarted = new CountDownLatch(1);
        CountDownLatch sourceMayFinish = new CountDownLatch(1);
        when(redisTemplate.opsForValue()).thenThrow(new DataAccessResourceFailureException("Redis 不可用"));
        when(realtimeSourceRefreshService.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.VISIBLE_AUTO_REFRESH))
                .thenAnswer(invocation -> {
                    sourceStarted.countDown();
                    sourceMayFinish.await(5, TimeUnit.SECONDS);
                    return new RefreshExecutionResult(true, 1L, 2L, 1, 1, "已發布快照");
                });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.VISIBLE_AUTO_REFRESH));
            assertThat(sourceStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var second = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);
            sourceMayFinish.countDown();

            assertThat(second.action()).isEqualTo("JOINED_RUNNING_REFRESH");
            assertThat(first.get(5, TimeUnit.SECONDS).action()).isEqualTo("REFRESHED_WITH_LOCAL_LOCK");
        }
        verify(realtimeSourceRefreshService, times(1))
                .refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.VISIBLE_AUTO_REFRESH);
    }

    @Test
    void recordsFailureAndDoesNotClaimPublishedData() {
        when(realtimeSourceRefreshService.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH))
                .thenThrow(new IllegalStateException("官方來源逾時"));

        var result = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);

        assertThat(result.action()).isEqualTo("FAILED");
        assertThat(sourceState.get().getRefreshStatus()).isEqualTo(RefreshStatus.FAILED);
        assertThat(sourceState.get().getFailureReason()).isEqualTo("官方來源逾時");
        assertThat(sourceState.get().getNextAllowedRefreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void preservesPublishingFailureCountWhileAddingCooldown() {
        SourceStateEntity existing = new SourceStateEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                RefreshStatus.FAILED,
                NOW
        );
        existing.setConsecutiveFailureCount(1);
        sourceState.set(existing);
        when(realtimeSourceRefreshService.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH))
                .thenReturn(new RefreshExecutionResult(false, 1L, null, 1, 0, "品質檢查未通過"));

        var result = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);

        assertThat(result.action()).isEqualTo("FAILED");
        assertThat(sourceState.get().getConsecutiveFailureCount()).isEqualTo(1);
        assertThat(sourceState.get().getNextAllowedRefreshAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }
}
