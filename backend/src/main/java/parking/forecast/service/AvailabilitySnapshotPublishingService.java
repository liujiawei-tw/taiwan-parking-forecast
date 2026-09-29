package parking.forecast.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.IngestionRunEntity;
import parking.forecast.domain.IngestionRunStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.RefreshStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.AvailabilityObservationRepository;
import parking.forecast.repository.AvailabilitySnapshotRepository;
import parking.forecast.repository.IngestionRunRepository;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.repository.SourceStateRepository;

@Service
public class AvailabilitySnapshotPublishingService {

    private static final String NO_OBSERVATIONS_REASON = "No normalized observations were provided.";
    private static final String NO_MAPPED_OBSERVATIONS_REASON = "No observations could be mapped to known parking places.";

    private final IngestionRunRepository ingestionRunRepository;
    private final AvailabilitySnapshotRepository availabilitySnapshotRepository;
    private final AvailabilityObservationRepository availabilityObservationRepository;
    private final SourceMappingRepository sourceMappingRepository;
    private final SourceStateRepository sourceStateRepository;

    public AvailabilitySnapshotPublishingService(
            IngestionRunRepository ingestionRunRepository,
            AvailabilitySnapshotRepository availabilitySnapshotRepository,
            AvailabilityObservationRepository availabilityObservationRepository,
            SourceMappingRepository sourceMappingRepository,
            SourceStateRepository sourceStateRepository
    ) {
        this.ingestionRunRepository = ingestionRunRepository;
        this.availabilitySnapshotRepository = availabilitySnapshotRepository;
        this.availabilityObservationRepository = availabilityObservationRepository;
        this.sourceMappingRepository = sourceMappingRepository;
        this.sourceStateRepository = sourceStateRepository;
    }

    @Transactional
    public PublishSnapshotResult publish(PublishSnapshotCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        command.validate();

        IngestionRunEntity ingestionRun = createIngestionRun(command);
        if (command.observations().isEmpty()) {
            return reject(command, ingestionRun, NO_OBSERVATIONS_REASON, 0, QualityStatus.FAIL);
        }

        AvailabilitySnapshotEntity snapshot = createCandidateSnapshot(command, ingestionRun);
        List<AvailabilityObservationEntity> entities = new ArrayList<>();
        int unmappedCount = 0;
        int failedObservationCount = 0;

        for (NormalizedAvailabilityObservation observation : command.observations()) {
            validateObservationSource(command, observation);
            if (observation.qualityStatus() == QualityStatus.FAIL) {
                failedObservationCount++;
            }

            SourceMappingEntity mapping = findVerifiedMapping(observation);
            if (mapping == null) {
                unmappedCount++;
                continue;
            }
            entities.add(toEntity(snapshot, mapping, observation, command.completedAt()));
        }

        if (entities.isEmpty()) {
            snapshot.setSnapshotStatus(SnapshotStatus.REJECTED);
            snapshot.setFinishedAt(command.completedAt());
            snapshot.setSystemFetchedAt(command.completedAt());
            snapshot.setActualRecordCount(0);
            snapshot.setQualityStatus(QualityStatus.FAIL);
            snapshot.setQualityReason(NO_MAPPED_OBSERVATIONS_REASON);
            availabilitySnapshotRepository.save(snapshot);
            return reject(command, ingestionRun, NO_MAPPED_OBSERVATIONS_REASON, 0, QualityStatus.FAIL);
        }

        availabilityObservationRepository.saveAll(entities);
        QualityStatus snapshotQuality = resolveSnapshotQuality(command.observations().size(), unmappedCount, failedObservationCount);
        String qualityReason = buildQualityReason(command.observations().size(), entities.size(), unmappedCount, failedObservationCount);

        snapshot.setSnapshotStatus(SnapshotStatus.PUBLISHED);
        snapshot.setFinishedAt(command.completedAt());
        snapshot.setSystemFetchedAt(resolveSystemFetchedAt(command));
        snapshot.setSourceObservedAt(resolveLatestSourceObservedAt(command.observations()));
        snapshot.setPublishedAt(command.completedAt());
        snapshot.setExpectedRecordCount(command.expectedRecordCount());
        snapshot.setActualRecordCount(entities.size());
        snapshot.setQualityStatus(snapshotQuality);
        snapshot.setQualityReason(qualityReason);
        availabilitySnapshotRepository.save(snapshot);

        ingestionRun.setRunStatus(IngestionRunStatus.SUCCEEDED);
        ingestionRun.setFinishedAt(command.completedAt());
        ingestionRun.setHttpRequestCount(command.httpRequestCount());
        ingestionRun.setRecordCount(entities.size());
        ingestionRun.setQualitySummary(resolveRunQualitySummary(command, qualityReason));
        ingestionRunRepository.save(ingestionRun);

        SourceStateEntity sourceState = sourceStateRepository.findBySourceSystemAndDatasetCode(
                command.sourceSystem(),
                command.datasetCode()
        ).orElseGet(() -> new SourceStateEntity(
                command.sourceSystem(),
                command.datasetCode(),
                RefreshStatus.IDLE,
                command.completedAt()
        ));
        sourceState.setActiveSnapshot(snapshot);
        sourceState.setLastSuccessfulFetchedAt(snapshot.getSystemFetchedAt());
        sourceState.setLastSourceObservedAt(snapshot.getSourceObservedAt());
        sourceState.setRefreshStatus(RefreshStatus.IDLE);
        sourceState.setFailureReason(null);
        sourceState.setConsecutiveFailureCount(0);
        sourceState.setUpdatedAt(command.completedAt());
        sourceStateRepository.save(sourceState);

        return new PublishSnapshotResult(
                ingestionRun.getId(),
                snapshot.getId(),
                true,
                snapshotQuality,
                entities.size(),
                unmappedCount,
                qualityReason
        );
    }

    private IngestionRunEntity createIngestionRun(PublishSnapshotCommand command) {
        IngestionRunEntity ingestionRun = new IngestionRunEntity(
                command.sourceSystem(),
                command.datasetCode(),
                command.triggerType(),
                IngestionRunStatus.RUNNING,
                command.startedAt()
        );
        ingestionRun.setHttpRequestCount(command.httpRequestCount());
        ingestionRun.setRecordCount(command.observations().size());
        return ingestionRunRepository.save(ingestionRun);
    }

    private AvailabilitySnapshotEntity createCandidateSnapshot(PublishSnapshotCommand command, IngestionRunEntity ingestionRun) {
        AvailabilitySnapshotEntity snapshot = new AvailabilitySnapshotEntity(
                command.sourceSystem(),
                command.datasetCode(),
                SnapshotStatus.CANDIDATE,
                command.startedAt(),
                QualityStatus.UNKNOWN
        );
        snapshot.setExpectedRecordCount(command.expectedRecordCount());
        snapshot.setIngestionRun(ingestionRun);
        return availabilitySnapshotRepository.save(snapshot);
    }

    private PublishSnapshotResult reject(
            PublishSnapshotCommand command,
            IngestionRunEntity ingestionRun,
            String reason,
            int publishedObservationCount,
            QualityStatus qualityStatus
    ) {
        ingestionRun.setRunStatus(IngestionRunStatus.REJECTED);
        ingestionRun.setFinishedAt(command.completedAt());
        ingestionRun.setHttpRequestCount(command.httpRequestCount());
        ingestionRun.setRecordCount(publishedObservationCount);
        ingestionRun.setFailureReason(reason);
        ingestionRun.setQualitySummary(resolveRunQualitySummary(command, reason));
        ingestionRunRepository.save(ingestionRun);

        SourceStateEntity sourceState = sourceStateRepository.findBySourceSystemAndDatasetCode(
                command.sourceSystem(),
                command.datasetCode()
        ).orElseGet(() -> new SourceStateEntity(
                command.sourceSystem(),
                command.datasetCode(),
                RefreshStatus.FAILED,
                command.completedAt()
        ));
        sourceState.setRefreshStatus(RefreshStatus.FAILED);
        sourceState.setFailureReason(reason);
        sourceState.setConsecutiveFailureCount(sourceState.getConsecutiveFailureCount() + 1);
        sourceState.setUpdatedAt(command.completedAt());
        sourceStateRepository.save(sourceState);

        return new PublishSnapshotResult(
                ingestionRun.getId(),
                null,
                false,
                qualityStatus,
                publishedObservationCount,
                command.observations().size(),
                reason
        );
    }

    private void validateObservationSource(PublishSnapshotCommand command, NormalizedAvailabilityObservation observation) {
        if (observation.sourceSystem() != command.sourceSystem() || observation.datasetCode() != command.datasetCode()) {
            throw new IllegalArgumentException("Observation source must match publish command source.");
        }
    }

    private SourceMappingEntity findVerifiedMapping(NormalizedAvailabilityObservation observation) {
        if (!StringUtils.hasText(observation.sourcePlaceId())) {
            return null;
        }
        return sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                observation.sourceSystem(),
                observation.datasetCode(),
                observation.sourcePlaceId()
        ).filter(mapping -> mapping.getMappingStatus() == MappingStatus.VERIFIED)
                .orElse(null);
    }

    private AvailabilityObservationEntity toEntity(
            AvailabilitySnapshotEntity snapshot,
            SourceMappingEntity mapping,
            NormalizedAvailabilityObservation observation,
            Instant createdAt
    ) {
        AvailabilityObservationEntity entity = new AvailabilityObservationEntity(
                snapshot,
                mapping.getParkingPlace(),
                observation.sourceSystem(),
                observation.datasetCode(),
                observation.sourcePlaceId(),
                observation.availabilityStatus(),
                observation.systemFetchedAt() == null ? createdAt : observation.systemFetchedAt(),
                observation.qualityStatus(),
                createdAt
        );
        entity.setAvailableCarSpaces(observation.availableCarSpaces());
        entity.setAvailableSpot(observation.availableSpot());
        entity.setRawStatusCode(observation.rawStatusCode());
        entity.setRawAvailabilityValue(observation.rawAvailabilityValue());
        entity.setSourceObservedAt(observation.sourceObservedAt());
        entity.setQualityReason(observation.qualityReason());
        return entity;
    }

    private QualityStatus resolveSnapshotQuality(int totalCount, int unmappedCount, int failedObservationCount) {
        if (unmappedCount == 0 && failedObservationCount == 0) {
            return QualityStatus.PASS;
        }
        if (unmappedCount < totalCount) {
            return QualityStatus.PARTIAL_PASS;
        }
        return QualityStatus.FAIL;
    }

    private String buildQualityReason(int totalCount, int publishedCount, int unmappedCount, int failedObservationCount) {
        return "normalized=%d, published=%d, unmapped=%d, failedObservations=%d".formatted(
                totalCount,
                publishedCount,
                unmappedCount,
                failedObservationCount
        );
    }

    private String resolveRunQualitySummary(PublishSnapshotCommand command, String fallback) {
        return StringUtils.hasText(command.measurementSummary()) ? command.measurementSummary() : fallback;
    }

    private Instant resolveSystemFetchedAt(PublishSnapshotCommand command) {
        return command.observations().stream()
                .map(NormalizedAvailabilityObservation::systemFetchedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(command.completedAt());
    }

    private Instant resolveLatestSourceObservedAt(List<NormalizedAvailabilityObservation> observations) {
        return observations.stream()
                .map(NormalizedAvailabilityObservation::sourceObservedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    public record PublishSnapshotCommand(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            TriggerType triggerType,
            Instant startedAt,
            Instant completedAt,
            int httpRequestCount,
            Integer expectedRecordCount,
            List<NormalizedAvailabilityObservation> observations,
            String measurementSummary
    ) {
        public PublishSnapshotCommand {
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        public PublishSnapshotCommand(
                SourceSystem sourceSystem,
                DatasetCode datasetCode,
                TriggerType triggerType,
                Instant startedAt,
                Instant completedAt,
                int httpRequestCount,
                Integer expectedRecordCount,
                List<NormalizedAvailabilityObservation> observations
        ) {
            this(
                    sourceSystem,
                    datasetCode,
                    triggerType,
                    startedAt,
                    completedAt,
                    httpRequestCount,
                    expectedRecordCount,
                    observations,
                    null
            );
        }

        private void validate() {
            if (sourceSystem == null) {
                throw new IllegalArgumentException("sourceSystem must not be null");
            }
            if (datasetCode == null) {
                throw new IllegalArgumentException("datasetCode must not be null");
            }
            if (triggerType == null) {
                throw new IllegalArgumentException("triggerType must not be null");
            }
            if (startedAt == null) {
                throw new IllegalArgumentException("startedAt must not be null");
            }
            if (completedAt == null) {
                throw new IllegalArgumentException("completedAt must not be null");
            }
            if (httpRequestCount < 0) {
                throw new IllegalArgumentException("httpRequestCount must not be negative");
            }
        }
    }

    public record PublishSnapshotResult(
            Long ingestionRunId,
            Long snapshotId,
            boolean published,
            QualityStatus qualityStatus,
            int publishedObservationCount,
            int rejectedObservationCount,
            String reason
    ) {
    }
}
