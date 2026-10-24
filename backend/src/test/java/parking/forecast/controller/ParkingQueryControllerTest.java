package parking.forecast.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.domain.MappingStatus;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ParkingQueryControllerTest {

    private static final Instant FRESH_FETCHED_AT = Instant.now().minusSeconds(60);
    private static final Instant STALE_FETCHED_AT = Instant.now().minusSeconds(600);

    @Autowired
    private ParkingQueryController controller;

    @Autowired
    private MockMvc mockMvc;

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

    @Autowired
    private SourceMappingRepository sourceMappingRepository;

    @Test
    void mapDataGetUsesPublishedSnapshotsAndReturnsAllReviewedStatusesWithinFixedRadius() throws Exception {
        publishRoadsideSnapshot(FRESH_FETCHED_AT, List.of(
                roadsideObservation(910106L, AvailabilityStatus.AVAILABLE, true),
                roadsideObservation(910107L, AvailabilityStatus.OCCUPIED, false)
        ));
        publishOffStreetSnapshot(FRESH_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.FULL, 0)
        ));
        var query = controller.create(mockRequestNearSeedCatalog()).getBody();

        var data = controller.getMapData(query.query().queryId());
        assertThat(data.radiusMeters()).isEqualTo(500);
        assertThat(data.roadside().snapshotId()).isNotNull();
        assertThat(data.offStreet().snapshotId()).isNotNull();
        assertThat(data.items()).filteredOn(item -> item.id().equals("spot-910106"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.availabilityStatus()).isEqualTo("AVAILABLE");
                    assertThat(item.navigationAvailable()).isTrue();
                    assertThat(item.sourceObservedAt()).isNotNull();
                    assertThat(item.systemCollectedAt()).isNotNull();
                });
        assertThat(data.items()).filteredOn(item -> item.id().equals("spot-910107"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.availabilityStatus()).isEqualTo("OCCUPIED");
                    assertThat(item.navigationAvailable()).isFalse();
                });
        assertThat(data.items()).filteredOn(item -> item.id().equals("lot-920101"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.availabilityStatus()).isEqualTo("FULL");
                    assertThat(item.availableCarSpaces()).isZero();
                    assertThat(item.navigationAvailable()).isFalse();
                });

        mockMvc.perform(get("/api/v1/parking-queries/{queryId}/map-data", query.query().queryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radiusMeters").value(500))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void mapDataGetShowsStaleAvailabilityAsStaleWithoutOldCounts() {
        publishOffStreetSnapshot(STALE_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.AVAILABLE, 12)
        ));
        var query = controller.create(mockRequestNearSeedCatalog()).getBody();

        var data = controller.getMapData(query.query().queryId());

        assertThat(data.radiusMeters()).isEqualTo(500);
        assertThat(data.items()).filteredOn(item -> item.id().equals("lot-920101"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.availabilityStatus()).isEqualTo("STALE");
                    assertThat(item.availableCarSpaces()).isNull();
                    assertThat(item.navigationAvailable()).isFalse();
                    assertThat(item.navigationLatitude()).isNull();
                    assertThat(item.navigationLongitude()).isNull();
                });
    }

    @Test
    void offStreetMapNavigationUsesTheSameEntranceCoordinatesAsTheExistingQueryResponse() {
        ParkingPlaceEntity parkingLot = parkingPlaceRepository.findById(920101L).orElseThrow();
        parkingLot.setEntranceLatitude(new BigDecimal("25.0425000"));
        parkingLot.setEntranceLongitude(new BigDecimal("121.4598000"));
        publishOffStreetSnapshot(FRESH_FETCHED_AT, List.of(
                offStreetObservation(920101L, AvailabilityStatus.AVAILABLE, 12)
        ));
        var query = controller.create(mockRequestNearSeedCatalog()).getBody();

        var legacyParkingLot = query.offStreet().parkingLots().stream()
                .filter(lot -> lot.id().equals("920101"))
                .findFirst()
                .orElseThrow();
        var mapMarker = controller.getMapData(query.query().queryId()).items().stream()
                .filter(item -> item.id().equals("lot-920101"))
                .findFirst()
                .orElseThrow();

        assertThat(legacyParkingLot.navigationAvailable()).isTrue();
        assertThat(mapMarker.navigationAvailable()).isTrue();
        assertThat(mapMarker.latitude()).isEqualTo(parkingLot.getLatitude().doubleValue());
        assertThat(mapMarker.longitude()).isEqualTo(parkingLot.getLongitude().doubleValue());
        assertThat(mapMarker.navigationLatitude()).isEqualTo(legacyParkingLot.latitude());
        assertThat(mapMarker.navigationLongitude()).isEqualTo(legacyParkingLot.longitude());
        assertThat(mapMarker.navigationLatitude()).isNotEqualTo(mapMarker.latitude());
        assertThat(mapMarker.navigationLongitude()).isNotEqualTo(mapMarker.longitude());
    }

    @Test
    void createQueryReturnsCatalogResultsAndNoRealtimeSnapshotWhenNoPublishedSnapshotExists() {
        var response = controller.create(mockRequestNearSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query().queryId()).isNotBlank();
        assertThat(response.getBody().query().status()).isEqualTo("READY");
        assertThat(response.getBody().area().radiusMeters()).isEqualTo(500);
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
        assertThat(response.getBody().predictions().targetArrivalAt()).isNull();
        assertThat(response.getBody().predictions().modelVersion()).isNull();
        assertThat(response.getBody().predictions().generatedAt()).isNotNull();
        assertThat(response.getBody().predictions().reasons())
                .contains("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED", "ETA_NOT_READY", "NO_REALTIME_SNAPSHOT");
        assertThat(response.getBody().predictions().roadside().probability()).isNull();
        assertThat(response.getBody().predictions().roadside().inputSnapshotVersion()).isNull();
        assertThat(response.getBody().predictions().roadside().dataAgeSeconds()).isNull();
        assertThat(response.getBody().predictions().roadside().reasons())
                .contains("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED", "ETA_NOT_READY",
                        "NO_REALTIME_SNAPSHOT", "CATALOG_COVERAGE_PARTIAL");
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
        assertThat(response.roadside().snapshotId()).isNotNull();
        assertThat(response.roadside().currentlyAvailableSpaces()).isEqualTo(1);
        assertThat(response.roadside().availableSpots())
                .extracting(spot -> spot.id())
                .containsExactly("910106");
        assertThat(response.roadside().availableSpots().getFirst().availabilityStatus()).isEqualTo("AVAILABLE");
        assertThat(response.offStreet().source()).isEqualTo("NTPC_OPEN_DATA");
        assertThat(response.offStreet().snapshotId()).isNotNull();
        assertThat(response.offStreet().parkingLots())
                .filteredOn(lot -> lot.id().equals("920101"))
                .singleElement()
                .satisfies(lot -> {
                    assertThat(lot.availabilityStatus()).isEqualTo("AVAILABLE");
                    assertThat(lot.availableCarSpaces()).isEqualTo(12);
                });
        assertThat(response.predictions().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.predictions().generatedAt()).isNotNull();
        assertThat(response.predictions().roadside().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.predictions().roadside().modelVersion()).isNull();
        assertThat(response.predictions().roadside().probability()).isNull();
        assertThat(response.predictions().roadside().inputSnapshotVersion())
                .isEqualTo("NTPC_ROADSIDE_SPOT:" + response.roadside().snapshotId());
        assertThat(response.predictions().roadside().dataAgeSeconds()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(response.predictions().roadside().reasons())
                .contains("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED", "ETA_NOT_READY",
                        "CATALOG_COVERAGE_PARTIAL");
        assertThat(response.predictions().offStreet())
                .filteredOn(prediction -> prediction.parkingLotId().equals("920101"))
                .singleElement()
                .satisfies(prediction -> {
                    assertThat(prediction.status()).isEqualTo("INSUFFICIENT_DATA");
                    assertThat(prediction.modelVersion()).isNull();
                    assertThat(prediction.probability()).isNull();
                    assertThat(prediction.inputSnapshotVersion())
                            .isEqualTo("NTPC_OFF_STREET_AVAILABILITY:" + response.offStreet().snapshotId());
                    assertThat(prediction.dataAgeSeconds()).isNotNull().isGreaterThanOrEqualTo(0);
                    assertThat(prediction.reasons())
                            .contains("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED", "ETA_NOT_READY");
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
        var refreshed = controller.refresh(created.query().queryId(), "MANUAL_REFRESH");

        assertThat(read.query().queryId()).isEqualTo(created.query().queryId());
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refreshed.getBody().refresh().roadside().action()).isEqualTo("FAILED_WITH_LOCAL_LOCK");
        assertThat(refreshed.getBody().refresh().offStreet().action()).isEqualTo("FAILED_WITH_LOCAL_LOCK");
    }

    @Test
    void createQueryReusesExistingQueryForDuplicateRequestToken() {
        var request = mockRequestNearSeedCatalog("mock-token-dedupe-" + UUID.randomUUID());

        var first = controller.create(request).getBody();
        var second = controller.create(request).getBody();

        assertThat(second.query().queryId()).isEqualTo(first.query().queryId());
        assertThat(second.query().createdAt()).isEqualTo(first.query().createdAt());
    }

    @Test
    void createQueryReturnsNoCatalogResultsWhenDestinationIsInsideMvpButOutsideSeedArea() {
        var response = controller.create(mockRequestInsideMvpButOutsideSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().area().coverageStatus()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().availableSpots()).isEmpty();
        assertThat(response.getBody().offStreet().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().offStreet().parkingLots()).isEmpty();
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(response.getBody().predictions().reasons())
                .contains("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED", "ETA_NOT_READY", "NO_CATALOG_RESULTS");
    }

    @Test
    void createQueryRejectsInvalidCoordinateWithStableError() throws Exception {
        mockMvc.perform(post("/api/v1/parking-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestToken": "mock-token-invalid-coordinate",
                                  "origin": { "label": "目前位置", "latitude": 91.0, "longitude": 121.45000 },
                                  "destination": { "label": "IKEA 新莊", "latitude": 25.0414214, "longitude": 121.4647633 },
                                  "departure": { "type": "NOW" }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_COORDINATE"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void createQueryRejectsUnsupportedAreaWithStableError() throws Exception {
        mockMvc.perform(post("/api/v1/parking-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestToken": "mock-token-unsupported-area",
                                  "origin": { "label": "目前位置", "latitude": 25.04000, "longitude": 121.45000 },
                                  "destination": { "label": "板橋測試點", "latitude": 25.01500, "longitude": 121.52000 },
                                  "departure": { "type": "NOW" }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_AREA"))
                .andExpect(jsonPath("$.error.details.serviceArea").value("NEW_TAIPEI_XINZHUANG"));
    }

    @Test
    void createQueryRejectsPastScheduledDepartureWithStableError() throws Exception {
        mockMvc.perform(post("/api/v1/parking-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestToken": "mock-token-past-departure",
                                  "origin": { "label": "目前位置", "latitude": 25.04000, "longitude": 121.45000 },
                                  "destination": { "label": "IKEA 新莊", "latitude": 25.0414214, "longitude": 121.4647633 },
                                  "departure": { "type": "SCHEDULED", "departureAt": "2026-01-01T09:00:00+08:00" }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_DEPARTURE_TIME"));
    }

    @Test
    void getUnknownQueryReturnsStableError() throws Exception {
        mockMvc.perform(get("/api/v1/parking-queries/not-found-query"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUERY_NOT_FOUND"));
    }

    @Test
    void refreshUnknownQueryReturnsStableError() throws Exception {
        mockMvc.perform(post("/api/v1/parking-queries/not-found-query/refresh"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUERY_NOT_FOUND"));
    }

    @Test
    void refreshRejectsUnsupportedTriggerWithStableError() throws Exception {
        var created = controller.create(mockRequestNearSeedCatalog()).getBody();

        mockMvc.perform(post("/api/v1/parking-queries/{queryId}/refresh", created.query().queryId())
                        .queryParam("trigger", "BACKGROUND_SAMPLING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TRIGGER"))
                .andExpect(jsonPath("$.error.retryable").value(false));
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
                    verifiedSourcePlaceId(datasetCode, observation.getParkingPlace().getId()),
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

    private String verifiedSourcePlaceId(DatasetCode datasetCode, Long parkingPlaceId) {
        return sourceMappingRepository.findBySourceSystemAndDatasetCodeAndMappingStatusAndParkingPlace_IdIn(
                        SourceSystem.NTPC_OPEN_DATA,
                        datasetCode,
                        MappingStatus.VERIFIED,
                        List.of(parkingPlaceId))
                .stream()
                .filter(mapping -> mapping.getParkingPlace().getId().equals(parkingPlaceId))
                .map(mapping -> mapping.getSourcePlaceId())
                .findFirst()
                .orElseThrow();
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
        return mockRequestNearSeedCatalog("mock-token-" + UUID.randomUUID());
    }

    private CreateParkingQueryRequest mockRequestNearSeedCatalog(String requestToken) {
        return new CreateParkingQueryRequest(
                requestToken,
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("IKEA 新莊", 25.0414214, 121.4647633),
                new DepartureInput("NOW", null));
    }

    private CreateParkingQueryRequest mockRequestInsideMvpButOutsideSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-" + UUID.randomUUID(),
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("新莊遠端測試點", 25.07000, 121.48000),
                new DepartureInput("NOW", null));
    }
}
