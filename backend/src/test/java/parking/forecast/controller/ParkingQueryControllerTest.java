package parking.forecast.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.IngestionRunEntity;
import parking.forecast.domain.IngestionRunStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.RefreshStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.AvailabilityObservationRepository;
import parking.forecast.repository.AvailabilitySnapshotRepository;
import parking.forecast.repository.IngestionRunRepository;
import parking.forecast.repository.ParkingPlaceRepository;
import parking.forecast.repository.SourceStateRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ParkingQueryControllerTest {

    private static final Instant FRESH_FETCHED_AT = Instant.now().minusSeconds(60);
    private static final Instant STALE_FETCHED_AT = Instant.now().minusSeconds(600);

    @Autowired
    private ParkingQueryController controller;

    @Autowired
    private ParkingPlaceRepository parkingPlaceRepository;

    @Autowired
    private IngestionRunRepository ingestionRunRepository;

    @Autowired
    private AvailabilitySnapshotRepository availabilitySnapshotRepository;

    @Autowired
    private AvailabilityObservationRepository availabilityObservationRepository;

    @Autowired
    private SourceStateRepository sourceStateRepository;

    @Test
    void createQueryReturnsCatalogResultsAndNoRealtimeSnapshotWhenNoPublishedSnapshotExists() {
        var response = controller.create(mockRequestNearSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query().queryId()).isNotBlank();
        assertThat(response.getBody().query().status()).isEqualTo("READY");
        assertThat(response.getBody().area().radiusMeters()).isEqualTo(300);
        assertThat(response.getBody().area().distanceMode()).isEqualTo("STRAIGHT_LINE");
        assertThat(response.getBody().area().coverageStatus()).isEqualTo("PARTIAL");
        assertThat(response.getBody().eta().status()).isEqualTo("FAILED");
        assertThat(response.getBody().eta().provider()).isEqualTo("GOOGLE_ROUTES");
        assertThat(response.getBody().eta().estimatedArrivalAt()).isNull();
        assertThat(response.getBody().roadside().source()).isEqualTo("LOCAL_NTPC_CATALOG");
        assertThat(response.getBody().roadside().status()).isEqualTo("NO_REALTIME_SNAPSHOT");
        assertThat(response.getBody().roadside().currentlyAvailableSpaces()).isNull();
        assertThat(response.getBody().roadside().availableSpots()).isEmpty();
        assertThat(response.getBody().offStreet().source()).isEqualTo("LOCAL_NTPC_CATALOG");
        assertThat(response.getBody().offStreet().status()).isEqualTo("NO_REALTIME_SNAPSHOT");
        assertThat(response.getBody().offStreet().parkingLots())
                .extracting(lot -> lot.name())
                .contains("宜家家居股份有限公司新莊分公司停車場");
        assertThat(response.getBody().offStreet().parkingLots())
                .allSatisfy(lot -> {
                    assertThat(lot.availableCarSpaces()).isNull();
                    assertThat(lot.availabilityStatus()).isEqualTo("NO_REALTIME_SNAPSHOT");
                });
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void createQueryReadsFreshPublishedSnapshotForCurrentAvailability() {
        publishRoadsideSnapshot(FRESH_FETCHED_AT, List.of(
                roadsideObservation(910106L, AvailabilityStatus.AVAILABLE, true),
                roadsideObservation(910107L, AvailabilityStatus.OCCUPIED, false)
        ));
        publishOffStreetSnapshot(FRESH_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.AVAILABLE, 12)
        ));

        var response = controller.create(mockRequestNearSeedCatalog()).getBody();

        assertThat(response.roadside().source()).isEqualTo("NTPC_OPEN_DATA");
        assertThat(response.roadside().status()).isEqualTo("READY");
        assertThat(response.roadside().currentlyAvailableSpaces()).isEqualTo(1);
        assertThat(response.roadside().availableSpots())
                .extracting(spot -> spot.id())
                .containsExactly("910106");
        assertThat(response.roadside().availableSpots().getFirst().availabilityStatus()).isEqualTo("AVAILABLE");
        assertThat(response.offStreet().source()).isEqualTo("NTPC_OPEN_DATA");
        assertThat(response.offStreet().parkingLots())
                .filteredOn(lot -> lot.id().equals("920101"))
                .singleElement()
                .satisfies(lot -> {
                    assertThat(lot.availabilityStatus()).isEqualTo("AVAILABLE");
                    assertThat(lot.availableCarSpaces()).isEqualTo(12);
                });
        assertThat(response.refresh().roadside().action()).isEqualTo("USED_PUBLISHED_SNAPSHOT");
    }

    @Test
    void createQueryKeepsValidZeroSpacesSeparateFromUnknown() {
        publishOffStreetSnapshot(FRESH_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.FULL, 0)
        ));

        var response = controller.create(mockRequestNearSeedCatalog()).getBody();

        assertThat(response.offStreet().status()).isEqualTo("READY");
        assertThat(response.offStreet().parkingLots())
                .filteredOn(lot -> lot.id().equals("920101"))
                .singleElement()
                .satisfies(lot -> {
                    assertThat(lot.availabilityStatus()).isEqualTo("FULL");
                    assertThat(lot.availableCarSpaces()).isZero();
                });
    }

    @Test
    void createQueryKeepsUnknownRoadsideObservationSeparateFromZeroOrUnavailable() {
        publishRoadsideSnapshot(FRESH_FETCHED_AT, List.of(
                roadsideObservation(910106L, AvailabilityStatus.UNKNOWN, null)
        ));

        var response = controller.create(mockRequestNearSeedCatalog()).getBody();

        assertThat(response.roadside().status()).isEqualTo("UNKNOWN");
        assertThat(response.roadside().currentlyAvailableSpaces()).isNull();
        assertThat(response.roadside().availableSpots()).isEmpty();
    }
    @Test
    void createQueryHidesCurrentAvailabilityWhenSnapshotIsStale() {
        publishRoadsideSnapshot(STALE_FETCHED_AT, List.of(
                roadsideObservation(910106L, AvailabilityStatus.AVAILABLE, true)
        ));
        publishOffStreetSnapshot(STALE_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.AVAILABLE, 8)
        ));

        var response = controller.create(mockRequestNearSeedCatalog()).getBody();

        assertThat(response.roadside().status()).isEqualTo("STALE");
        assertThat(response.roadside().currentlyAvailableSpaces()).isNull();
        assertThat(response.roadside().availableSpots()).isEmpty();
        assertThat(response.offStreet().status()).isEqualTo("STALE");
        assertThat(response.offStreet().parkingLots())
                .filteredOn(lot -> lot.id().equals("920101"))
                .singleElement()
                .satisfies(lot -> {
                    assertThat(lot.availabilityStatus()).isEqualTo("STALE");
                    assertThat(lot.availableCarSpaces()).isNull();
                });
        assertThat(response.refresh().roadside().action()).isEqualTo("USED_STALE_PUBLISHED_SNAPSHOT");
    }

    @Test
    void createdQueryCanBeReadAndRefreshed() {
        var created = controller.create(mockRequestNearSeedCatalog()).getBody();

        var read = controller.get(created.query().queryId());
        var refreshed = controller.refresh(created.query().queryId());

        assertThat(read.query().queryId()).isEqualTo(created.query().queryId());
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refreshed.getBody().refresh().roadside().action()).isEqualTo("FAILED_WITH_LOCAL_LOCK");
        assertThat(refreshed.getBody().refresh().offStreet().action()).isEqualTo("FAILED_WITH_LOCAL_LOCK");
    }

    @Test
    void createQueryReturnsNoCatalogResultsWhenDestinationIsOutsideSeedArea() {
        var response = controller.create(mockRequestOutsideSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().area().coverageStatus()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().availableSpots()).isEmpty();
        assertThat(response.getBody().offStreet().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().offStreet().parkingLots()).isEmpty();
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    private void publishRoadsideSnapshot(Instant fetchedAt, List<AvailabilityObservationEntity> observations) {
        publishSnapshot(DatasetCode.NTPC_ROADSIDE_SPOT, fetchedAt, observations);
    }

    private void publishOffStreetSnapshot(Instant fetchedAt, List<AvailabilityObservationEntity> observations) {
        publishSnapshot(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, fetchedAt, observations);
    }

    private void publishSnapshot(
            DatasetCode datasetCode,
            Instant fetchedAt,
            List<AvailabilityObservationEntity> observations
    ) {
        IngestionRunEntity run = ingestionRunRepository.save(new IngestionRunEntity(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                TriggerType.INITIAL_QUERY,
                IngestionRunStatus.SUCCEEDED,
                fetchedAt.minusSeconds(3)
        ));
        AvailabilitySnapshotEntity snapshot = new AvailabilitySnapshotEntity(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                SnapshotStatus.PUBLISHED,
                fetchedAt.minusSeconds(3),
                QualityStatus.PASS
        );
        snapshot.setFinishedAt(fetchedAt);
        snapshot.setSystemFetchedAt(fetchedAt);
        snapshot.setSourceObservedAt(fetchedAt.minusSeconds(20));
        snapshot.setPublishedAt(fetchedAt.plusSeconds(1));
        snapshot.setActualRecordCount(observations.size());
        snapshot.setIngestionRun(run);
        AvailabilitySnapshotEntity savedSnapshot = availabilitySnapshotRepository.save(snapshot);

        observations.forEach(observation -> {
            AvailabilityObservationEntity persisted = new AvailabilityObservationEntity(
                    savedSnapshot,
                    observation.getParkingPlace(),
                    SourceSystem.NTPC_OPEN_DATA,
                    datasetCode,
                    observation.getSourcePlaceId(),
                    observation.getAvailabilityStatus(),
                    fetchedAt,
                    observation.getQualityStatus(),
                    fetchedAt
            );
            persisted.setAvailableCarSpaces(observation.getAvailableCarSpaces());
            persisted.setAvailableSpot(observation.getAvailableSpot());
            persisted.setSourceObservedAt(fetchedAt.minusSeconds(20));
            availabilityObservationRepository.save(persisted);
        });

        SourceStateEntity sourceState = new SourceStateEntity(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                RefreshStatus.IDLE,
                fetchedAt.plusSeconds(1)
        );
        sourceState.setActiveSnapshot(savedSnapshot);
        sourceState.setLastSuccessfulFetchedAt(fetchedAt);
        sourceState.setLastSourceObservedAt(fetchedAt.minusSeconds(20));
        sourceStateRepository.save(sourceState);
    }

    private AvailabilityObservationEntity roadsideObservation(
            Long parkingPlaceId,
            AvailabilityStatus status,
            Boolean availableSpot
    ) {
        ParkingPlaceEntity place = parkingPlaceRepository.findById(parkingPlaceId).orElseThrow();
        AvailabilityObservationEntity observation = new AvailabilityObservationEntity(
                null,
                place,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "NTPC-SPOT-" + parkingPlaceId,
                status,
                FRESH_FETCHED_AT,
                status == AvailabilityStatus.UNKNOWN ? QualityStatus.FAIL : QualityStatus.PASS,
                FRESH_FETCHED_AT
        );
        observation.setAvailableSpot(availableSpot);
        return observation;
    }

    private AvailabilityObservationEntity offStreetObservation(
            Long parkingPlaceId,
            AvailabilityStatus status,
            Integer availableCarSpaces
    ) {
        ParkingPlaceEntity place = parkingPlaceRepository.findById(parkingPlaceId).orElseThrow();
        AvailabilityObservationEntity observation = new AvailabilityObservationEntity(
                null,
                place,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                "NTPC-LOT-" + parkingPlaceId,
                status,
                FRESH_FETCHED_AT,
                status == AvailabilityStatus.UNKNOWN ? QualityStatus.FAIL : QualityStatus.PASS,
                FRESH_FETCHED_AT
        );
        observation.setAvailableCarSpaces(availableCarSpaces);
        return observation;
    }

    private CreateParkingQueryRequest mockRequestNearSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-001",
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("IKEA 新莊", 25.0414214, 121.4647633),
                new DepartureInput("NOW", null));
    }

    private CreateParkingQueryRequest mockRequestOutsideSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-002",
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("新莊遠端測試點", 25.08000, 121.50000),
                new DepartureInput("NOW", null));
    }
}
