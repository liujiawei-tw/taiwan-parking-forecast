package parking.forecast.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

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
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.RefreshStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DataModelRepositoryTest {

    @Autowired
    private ParkingPlaceRepository parkingPlaceRepository;

    @Autowired
    private SourceMappingRepository sourceMappingRepository;

    @Autowired
    private IngestionRunRepository ingestionRunRepository;

    @Autowired
    private AvailabilitySnapshotRepository availabilitySnapshotRepository;

    @Autowired
    private AvailabilityObservationRepository availabilityObservationRepository;

    @Autowired
    private SourceStateRepository sourceStateRepository;

    @Test
    void coreEntitiesCanBePersistedAndQueriedTogether() {
        Instant now = Instant.parse("2026-09-26T02:30:00Z");

        ParkingPlaceEntity place = parkingPlaceRepository.save(new ParkingPlaceEntity(
                ParkingPlaceType.ON_STREET_SPOT,
                "新莊測試汽車格位 A",
                new BigDecimal("25.0360000"),
                new BigDecimal("121.4520000"),
                EligibilityStatus.CONFIRMED,
                ActiveStatus.ACTIVE,
                "NEW_TAIPEI_XINZHUANG",
                now,
                now
        ));

        SourceMappingEntity mapping = sourceMappingRepository.save(new SourceMappingEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "XINZHUANG-SPOT-001",
                place,
                MappingStatus.VERIFIED,
                now,
                now
        ));

        IngestionRunEntity run = ingestionRunRepository.save(new IngestionRunEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                TriggerType.INITIAL_QUERY,
                IngestionRunStatus.SUCCEEDED,
                now
        ));
        run.setFinishedAt(now.plusSeconds(3));
        run.setHttpRequestCount(2);
        run.setRecordCount(1);

        AvailabilitySnapshotEntity snapshot = new AvailabilitySnapshotEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                SnapshotStatus.PUBLISHED,
                now,
                QualityStatus.PASS
        );
        snapshot.setFinishedAt(now.plusSeconds(3));
        snapshot.setSystemFetchedAt(now.plusSeconds(3));
        snapshot.setPublishedAt(now.plusSeconds(4));
        snapshot.setActualRecordCount(1);
        snapshot.setIngestionRun(run);
        snapshot = availabilitySnapshotRepository.save(snapshot);

        AvailabilityObservationEntity observation = new AvailabilityObservationEntity(
                snapshot,
                place,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "XINZHUANG-SPOT-001",
                AvailabilityStatus.AVAILABLE,
                now.plusSeconds(3),
                QualityStatus.PASS,
                now.plusSeconds(3)
        );
        observation.setAvailableSpot(true);
        observation.setRawStatusCode("0");
        observation = availabilityObservationRepository.save(observation);

        SourceStateEntity sourceState = new SourceStateEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                RefreshStatus.IDLE,
                now.plusSeconds(4)
        );
        sourceState.setActiveSnapshot(snapshot);
        sourceState.setLastSuccessfulFetchedAt(now.plusSeconds(3));
        sourceState.setNextAllowedRefreshAt(now.plusSeconds(303));
        sourceStateRepository.save(sourceState);

        assertThat(place.getId()).isNotNull();
        assertThat(mapping.getId()).isNotNull();
        assertThat(run.getId()).isNotNull();
        assertThat(snapshot.getId()).isNotNull();
        assertThat(observation.getId()).isNotNull();

        assertThat(parkingPlaceRepository.findByServiceAreaAndActiveStatus("NEW_TAIPEI_XINZHUANG", ActiveStatus.ACTIVE))
                .extracting(ParkingPlaceEntity::getDisplayName)
                .contains("新莊測試汽車格位 A");

        assertThat(sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "XINZHUANG-SPOT-001"
        )).isPresent();

        assertThat(availabilitySnapshotRepository.findBySourceSystemAndDatasetCodeAndSnapshotStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                SnapshotStatus.PUBLISHED
        )).hasSize(1);

        assertThat(availabilityObservationRepository.findBySnapshot(snapshot))
                .extracting(AvailabilityObservationEntity::getAvailabilityStatus)
                .containsExactly(AvailabilityStatus.AVAILABLE);

        assertThat(sourceStateRepository.findBySourceSystemAndDatasetCode(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT
        )).isPresent();
    }
}
