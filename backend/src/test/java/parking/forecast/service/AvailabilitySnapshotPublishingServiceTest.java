package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.IngestionRunEntity;
import parking.forecast.domain.IngestionRunStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
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
import parking.forecast.repository.ParkingPlaceRepository;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.repository.SourceStateRepository;
import parking.forecast.service.AvailabilitySnapshotPublishingService.PublishSnapshotCommand;
import parking.forecast.service.AvailabilitySnapshotPublishingService.PublishSnapshotResult;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AvailabilitySnapshotPublishingServiceTest {

    private static final SourceSystem SOURCE_SYSTEM = SourceSystem.NTPC_OPEN_DATA;
    private static final DatasetCode DATASET_CODE = DatasetCode.NTPC_ROADSIDE_SPOT;
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T02:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-09-28T02:00:05Z");

    @Autowired
    private AvailabilitySnapshotPublishingService publishingService;

    @Autowired
    private ParkingPlaceRepository parkingPlaceRepository;

    @Autowired
    private SourceMappingRepository sourceMappingRepository;

    @Autowired
    private AvailabilitySnapshotRepository availabilitySnapshotRepository;

    @Autowired
    private AvailabilityObservationRepository availabilityObservationRepository;

    @Autowired
    private IngestionRunRepository ingestionRunRepository;

    @Autowired
    private SourceStateRepository sourceStateRepository;

    @Test
    void publishesSnapshotAndUpdatesActiveSourceStateWhenQualityPasses() {
        createMappedPlace("NTPC-SPOT-001", "新莊測試格位 001");

        PublishSnapshotResult result = publishingService.publish(command(List.of(availableObservation("NTPC-SPOT-001"))));

        assertThat(result.published()).isTrue();
        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.PASS);
        assertThat(result.publishedObservationCount()).isEqualTo(1);
        assertThat(result.snapshotId()).isNotNull();

        AvailabilitySnapshotEntity snapshot = availabilitySnapshotRepository.findById(result.snapshotId()).orElseThrow();
        assertThat(snapshot.getSnapshotStatus()).isEqualTo(SnapshotStatus.PUBLISHED);
        assertThat(snapshot.getActualRecordCount()).isEqualTo(1);
        assertThat(snapshot.getPublishedAt()).isEqualTo(COMPLETED_AT);
        IngestionRunEntity run = ingestionRunRepository.findById(result.ingestionRunId()).orElseThrow();
        assertThat(run.getQualitySummary()).isEqualTo("sourceMeasurement{httpRequests=2, responseBytes=4096, recommendation=PUBLISH_MEETS_CURRENT_MVP_THRESHOLD}");

        List<AvailabilityObservationEntity> observations = availabilityObservationRepository.findBySnapshot(snapshot);
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(observations.getFirst().getAvailableSpot()).isTrue();
        assertThat(observations.getFirst().getRawStatusCode()).isEqualTo("0");

        SourceStateEntity sourceState = sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, DATASET_CODE)
                .orElseThrow();
        assertThat(sourceState.getActiveSnapshot().getId()).isEqualTo(snapshot.getId());
        assertThat(sourceState.getRefreshStatus()).isEqualTo(RefreshStatus.IDLE);
        assertThat(sourceState.getConsecutiveFailureCount()).isZero();
        assertThat(sourceState.getFailureReason()).isNull();
    }

    @Test
    void rejectionDoesNotReplaceExistingActiveSnapshotWhenNoObservationCanBeMapped() {
        AvailabilitySnapshotEntity previousSnapshot = createPublishedSnapshot();
        SourceStateEntity previousState = new SourceStateEntity(SOURCE_SYSTEM, DATASET_CODE, RefreshStatus.IDLE, STARTED_AT.minusSeconds(30));
        previousState.setActiveSnapshot(previousSnapshot);
        previousState.setLastSuccessfulFetchedAt(previousSnapshot.getSystemFetchedAt());
        previousState.setConsecutiveFailureCount(1);
        sourceStateRepository.save(previousState);

        PublishSnapshotResult result = publishingService.publish(command(List.of(availableObservation("UNMAPPED-SPOT"))));

        assertThat(result.published()).isFalse();
        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.FAIL);
        assertThat(result.reason()).contains("No observations could be mapped");

        SourceStateEntity sourceState = sourceStateRepository.findBySourceSystemAndDatasetCode(SOURCE_SYSTEM, DATASET_CODE)
                .orElseThrow();
        assertThat(sourceState.getActiveSnapshot().getId()).isEqualTo(previousSnapshot.getId());
        assertThat(sourceState.getRefreshStatus()).isEqualTo(RefreshStatus.FAILED);
        assertThat(sourceState.getConsecutiveFailureCount()).isEqualTo(2);
        assertThat(sourceState.getFailureReason()).contains("No observations could be mapped");

        List<AvailabilitySnapshotEntity> publishedSnapshots = availabilitySnapshotRepository
                .findBySourceSystemAndDatasetCodeAndSnapshotStatus(SOURCE_SYSTEM, DATASET_CODE, SnapshotStatus.PUBLISHED);
        assertThat(publishedSnapshots).extracting(AvailabilitySnapshotEntity::getId).contains(previousSnapshot.getId());
    }

    @Test
    void unknownObservationIsPersistedWithoutBlockingSnapshotPublication() {
        createMappedPlace("NTPC-SPOT-UNKNOWN", "新莊測試格位 Unknown");

        PublishSnapshotResult result = publishingService.publish(command(List.of(unknownObservation("NTPC-SPOT-UNKNOWN"))));

        assertThat(result.published()).isTrue();
        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);

        AvailabilitySnapshotEntity snapshot = availabilitySnapshotRepository.findById(result.snapshotId()).orElseThrow();
        List<AvailabilityObservationEntity> observations = availabilityObservationRepository.findBySnapshot(snapshot);
        assertThat(observations).hasSize(1);
        AvailabilityObservationEntity observation = observations.getFirst();
        assertThat(observation.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.UNKNOWN);
        assertThat(observation.getQualityStatus()).isEqualTo(QualityStatus.FAIL);
        assertThat(observation.getQualityReason()).isEqualTo("cellstatus is missing");
        assertThat(observation.getAvailableSpot()).isNull();
    }

    @Test
    void emptyObservationListRecordsRejectedRunAndDoesNotPublishSnapshot() {
        PublishSnapshotResult result = publishingService.publish(command(List.of()));

        assertThat(result.published()).isFalse();
        assertThat(result.snapshotId()).isNull();
        assertThat(result.reason()).contains("No normalized observations");

        IngestionRunEntity run = ingestionRunRepository.findById(result.ingestionRunId()).orElseThrow();
        assertThat(run.getRunStatus()).isEqualTo(IngestionRunStatus.REJECTED);
        assertThat(run.getFailureReason()).contains("No normalized observations");

        assertThat(availabilitySnapshotRepository.findBySourceSystemAndDatasetCodeAndSnapshotStatus(
                SOURCE_SYSTEM,
                DATASET_CODE,
                SnapshotStatus.PUBLISHED
        )).isEmpty();
    }

    private PublishSnapshotCommand command(List<NormalizedAvailabilityObservation> observations) {
        return new PublishSnapshotCommand(
                SOURCE_SYSTEM,
                DATASET_CODE,
                TriggerType.INITIAL_QUERY,
                STARTED_AT,
                COMPLETED_AT,
                2,
                observations.size(),
                observations,
                "sourceMeasurement{httpRequests=2, responseBytes=4096, recommendation=PUBLISH_MEETS_CURRENT_MVP_THRESHOLD}"
        );
    }

    private NormalizedAvailabilityObservation availableObservation(String sourcePlaceId) {
        return NormalizedAvailabilityObservation.builder()
                .sourceSystem(SOURCE_SYSTEM)
                .datasetCode(DATASET_CODE)
                .sourcePlaceId(sourcePlaceId)
                .availabilityStatus(AvailabilityStatus.AVAILABLE)
                .availableSpot(true)
                .rawStatusCode("0")
                .sourceObservedAt(STARTED_AT.plusSeconds(1))
                .systemFetchedAt(COMPLETED_AT)
                .qualityStatus(QualityStatus.PASS)
                .carEligibleStatus(EligibilityStatus.CONFIRMED)
                .currentlyRecommendable(true)
                .build();
    }

    private NormalizedAvailabilityObservation unknownObservation(String sourcePlaceId) {
        return NormalizedAvailabilityObservation.builder()
                .sourceSystem(SOURCE_SYSTEM)
                .datasetCode(DATASET_CODE)
                .sourcePlaceId(sourcePlaceId)
                .availabilityStatus(AvailabilityStatus.UNKNOWN)
                .rawStatusCode("")
                .sourceObservedAt(STARTED_AT.plusSeconds(1))
                .systemFetchedAt(COMPLETED_AT)
                .qualityStatus(QualityStatus.FAIL)
                .qualityReason("cellstatus is missing")
                .carEligibleStatus(EligibilityStatus.CONFIRMED)
                .currentlyRecommendable(false)
                .build();
    }

    private ParkingPlaceEntity createMappedPlace(String sourcePlaceId, String displayName) {
        Instant now = STARTED_AT.minusSeconds(60);
        ParkingPlaceEntity place = parkingPlaceRepository.save(new ParkingPlaceEntity(
                ParkingPlaceType.ON_STREET_SPOT,
                displayName,
                new BigDecimal("25.0372000"),
                new BigDecimal("121.4525000"),
                EligibilityStatus.CONFIRMED,
                ActiveStatus.ACTIVE,
                "NEW_TAIPEI_XINZHUANG",
                now,
                now
        ));
        sourceMappingRepository.save(new SourceMappingEntity(
                SOURCE_SYSTEM,
                DATASET_CODE,
                sourcePlaceId,
                place,
                MappingStatus.VERIFIED,
                now,
                now
        ));
        return place;
    }

    private AvailabilitySnapshotEntity createPublishedSnapshot() {
        IngestionRunEntity run = ingestionRunRepository.save(new IngestionRunEntity(
                SOURCE_SYSTEM,
                DATASET_CODE,
                TriggerType.INITIAL_QUERY,
                IngestionRunStatus.SUCCEEDED,
                STARTED_AT.minusSeconds(60)
        ));
        AvailabilitySnapshotEntity snapshot = new AvailabilitySnapshotEntity(
                SOURCE_SYSTEM,
                DATASET_CODE,
                SnapshotStatus.PUBLISHED,
                STARTED_AT.minusSeconds(60),
                QualityStatus.PASS
        );
        snapshot.setFinishedAt(STARTED_AT.minusSeconds(55));
        snapshot.setSystemFetchedAt(STARTED_AT.minusSeconds(55));
        snapshot.setPublishedAt(STARTED_AT.minusSeconds(54));
        snapshot.setActualRecordCount(1);
        snapshot.setIngestionRun(run);
        return availabilitySnapshotRepository.save(snapshot);
    }
}
