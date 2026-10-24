package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import parking.forecast.config.ParkingQueryStateProperties;
import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.GoogleEtaService.EtaResult;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingPlace;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedObservation;
import parking.forecast.service.ParkingQueryService.ParkingQueryExpiredException;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedAvailabilityView;

class ParkingQueryServiceTest {

    @Test
    void mapDataKeepsUnconfirmedCarEligibilityNeutralAndDoesNotRefreshSourcesOrRoutes() {
        TestFixture fixture = new TestFixture();
        var created = fixture.service.create(mockRequestNearSeedCatalog());
        OffsetDateTime observedAt = OffsetDateTime.ofInstant(fixture.clock.instant(), ZoneOffset.UTC);
        NearbyParkingPlace place = new NearbyParkingPlace(
                910106L,
                ParkingPlaceType.ON_STREET_SPOT,
                "測試路邊格位",
                25.043188,
                121.464997,
                null,
                null,
                EligibilityStatus.UNKNOWN,
                198
        );
        when(fixture.nearbyParkingSearchService.findNearby(any()))
                .thenReturn(new NearbyParkingSearchResult(
                        "NEW_TAIPEI_XINZHUANG",
                        25.0414214,
                        121.4647633,
                        500,
                        "STRAIGHT_LINE",
                        List.of(place),
                        1,
                        0
                ));
        when(fixture.publishedAvailabilitySnapshotService.findLatest(eq(DatasetCode.NTPC_ROADSIDE_SPOT), any()))
                .thenReturn(new PublishedAvailabilityView(
                        DatasetCode.NTPC_ROADSIDE_SPOT,
                        "READY",
                        99L,
                        observedAt,
                        observedAt,
                        Map.of(910106L, new PublishedObservation(
                                910106L,
                                "96316",
                                AvailabilityStatus.AVAILABLE,
                                null,
                                true,
                                observedAt,
                                observedAt,
                                QualityStatus.PASS,
                                null
                        )),
                        "ready"
                ));
        SourceMappingEntity mapping = mock(SourceMappingEntity.class);
        ParkingPlaceEntity mappedPlace = mock(ParkingPlaceEntity.class);
        when(mapping.getParkingPlace()).thenReturn(mappedPlace);
        when(mappedPlace.getId()).thenReturn(910106L);
        when(mapping.getSourcePlaceId()).thenReturn("96316");
        when(fixture.sourceMappingRepository.findBySourceSystemAndDatasetCodeAndMappingStatusAndParkingPlace_IdIn(
                eq(SourceSystem.NTPC_OPEN_DATA),
                eq(DatasetCode.NTPC_ROADSIDE_SPOT),
                eq(MappingStatus.VERIFIED),
                any()
        )).thenReturn(List.of(mapping));

        var data = fixture.service.findMapData(created.query().queryId());

        assertThat(data.items()).singleElement().satisfies(item -> {
            assertThat(item.availabilityStatus()).isEqualTo("AVAILABLE");
            assertThat(item.carEligibleStatus()).isEqualTo("UNKNOWN");
            assertThat(item.availableSpot()).isNull();
            assertThat(item.navigationAvailable()).isFalse();
        });
        verify(fixture.sourceRefreshCoordinationService, never()).refresh(any(), any());
        verify(fixture.googleEtaService, times(1)).estimate(any(), any(), any(), anyBoolean());
    }

    @Test
    void mapDataFiltersVerifiedMappingsBeforeApplyingStableDistanceCap() {
        TestFixture fixture = new TestFixture();
        var created = fixture.service.create(mockRequestNearSeedCatalog());
        long firstId = 930_000L;
        List<NearbyParkingPlace> places = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            places.add(roadsidePlace(firstId + index, index + 1));
        }
        for (int verifiedIndex = 0; verifiedIndex < 399; verifiedIndex++) {
            places.add(roadsidePlace(firstId + 10 + verifiedIndex, 11 + verifiedIndex));
        }
        // Put the higher id first in the equal-distance boundary pair. Stable id ordering must keep the lower one.
        places.add(roadsidePlace(firstId + 410, 410));
        places.add(roadsidePlace(firstId + 409, 410));
        for (int verifiedIndex = 401; verifiedIndex < 505; verifiedIndex++) {
            places.add(roadsidePlace(firstId + 10 + verifiedIndex, 10 + verifiedIndex));
        }
        when(fixture.nearbyParkingSearchService.findNearby(any()))
                .thenReturn(new NearbyParkingSearchResult(
                        "NEW_TAIPEI_XINZHUANG",
                        25.0414214,
                        121.4647633,
                        500,
                        "STRAIGHT_LINE",
                        places,
                        places.size(),
                        0
                ));
        List<Integer> mappingBatchSizes = new ArrayList<>();
        long ambiguousPlaceId = firstId + 9;
        when(fixture.sourceMappingRepository.findBySourceSystemAndDatasetCodeAndMappingStatusAndParkingPlace_IdIn(
                eq(SourceSystem.NTPC_OPEN_DATA),
                eq(DatasetCode.NTPC_ROADSIDE_SPOT),
                eq(MappingStatus.VERIFIED),
                any()
        )).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(3);
            mappingBatchSizes.add(ids.size());
            List<SourceMappingEntity> mappings = new ArrayList<>();
            ids.stream()
                    .filter(id -> id >= firstId + 10 || id == ambiguousPlaceId)
                    .forEach(id -> {
                        mappings.add(verifiedMapping(id));
                        if (id == ambiguousPlaceId) mappings.add(verifiedMapping(id));
                    });
            return mappings;
        });
        clearInvocations(fixture.sourceMappingRepository, fixture.publishedAvailabilitySnapshotService);

        var data = fixture.service.findMapData(created.query().queryId());

        assertThat(data.candidateCount()).isEqualTo(515);
        assertThat(data.verifiedCandidateCount()).isEqualTo(505);
        assertThat(data.excludedUnverifiedCount()).isEqualTo(10);
        assertThat(data.omittedItemCount()).isEqualTo(105);
        assertThat(data.returnedItemCount()).isEqualTo(400);
        assertThat(data.items()).hasSize(400);
        assertThat(data.items()).extracting(item -> item.id())
                .contains("spot-" + (firstId + 409))
                .doesNotContain("spot-" + (firstId + 410), "spot-" + ambiguousPlaceId);

        verify(fixture.sourceMappingRepository, times(2))
                .findBySourceSystemAndDatasetCodeAndMappingStatusAndParkingPlace_IdIn(
                        eq(SourceSystem.NTPC_OPEN_DATA),
                        eq(DatasetCode.NTPC_ROADSIDE_SPOT),
                        eq(MappingStatus.VERIFIED),
                        any()
                );
        assertThat(mappingBatchSizes).containsExactly(500, 15);

        verify(fixture.publishedAvailabilitySnapshotService)
                .findLatest(eq(DatasetCode.NTPC_ROADSIDE_SPOT), argThat(ids -> ids.size() == 400));
    }

    @Test
    void findByIdRejectsExpiredQuery() {
        TestFixture fixture = new TestFixture();

        var created = fixture.service.create(mockRequestNearSeedCatalog());
        fixture.clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> fixture.service.findById(created.query().queryId()))
                .isInstanceOf(ParkingQueryExpiredException.class);
    }

    @Test
    void refreshUsesVisibleAutoRefreshTrigger() {
        TestFixture fixture = new TestFixture();
        when(fixture.sourceRefreshCoordinationService.refresh(any(), eq(TriggerType.VISIBLE_AUTO_REFRESH)))
                .thenAnswer(invocation -> SourceRefreshCoordinationService.SourceRefreshResult.failed(
                        invocation.getArgument(0, DatasetCode.class),
                        null,
                        null,
                        "not available",
                        true
                ));
        var created = fixture.service.create(mockRequestNearSeedCatalog());

        fixture.service.refresh(created.query().queryId(), TriggerType.VISIBLE_AUTO_REFRESH);

        verify(fixture.sourceRefreshCoordinationService)
                .refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.VISIBLE_AUTO_REFRESH);
        verify(fixture.sourceRefreshCoordinationService)
                .refresh(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, TriggerType.VISIBLE_AUTO_REFRESH);
    }

    @Test
    void refreshAfterArrivalPreservesEtaAndMarksPredictionExpired() {
        TestFixture fixture = new TestFixture();
        var created = fixture.service.create(mockRequestNearSeedCatalog());
        fixture.clock.advance(Duration.ofMinutes(21));

        var refreshed = fixture.service.refresh(created.query().queryId());

        assertThat(refreshed.eta()).isEqualTo(created.eta());
        assertThat(refreshed.predictions().reasons()).contains("ETA_EXPIRED");
        assertThat(refreshed.predictions().roadside().probability()).isNull();
        verify(fixture.googleEtaService).estimate(any(), any(), any(), anyBoolean());
    }

    private CreateParkingQueryRequest mockRequestNearSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-" + UUID.randomUUID(),
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("IKEA 新莊", 25.0414214, 121.4647633),
                new DepartureInput("NOW", null));
    }

    private NearbyParkingPlace roadsidePlace(long id, int distanceMeters) {
        return new NearbyParkingPlace(
                id,
                ParkingPlaceType.ON_STREET_SPOT,
                "路邊格位 " + id,
                25.043188,
                121.464997,
                null,
                null,
                EligibilityStatus.CONFIRMED,
                distanceMeters
        );
    }

    private SourceMappingEntity verifiedMapping(Long parkingPlaceId) {
        SourceMappingEntity mapping = mock(SourceMappingEntity.class);
        ParkingPlaceEntity place = mock(ParkingPlaceEntity.class);
        when(mapping.getParkingPlace()).thenReturn(place);
        when(place.getId()).thenReturn(parkingPlaceId);
        when(mapping.getSourcePlaceId()).thenReturn("verified-" + parkingPlaceId);
        return mapping;
    }

    private static class TestFixture {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T01:00:00Z"));
        private final NearbyParkingSearchService nearbyParkingSearchService = mock(NearbyParkingSearchService.class);
        private final PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService = mock(PublishedAvailabilitySnapshotService.class);
        private final SourceRefreshCoordinationService sourceRefreshCoordinationService = mock(SourceRefreshCoordinationService.class);
        private final GoogleEtaService googleEtaService = mock(GoogleEtaService.class);
        private final SourceMappingRepository sourceMappingRepository = mock(SourceMappingRepository.class);
        private final ParkingQueryService service;

        private TestFixture() {
            ParkingQueryStateProperties queryStateProperties = new ParkingQueryStateProperties();
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            when(googleEtaService.estimate(any(), any(), any(), anyBoolean()))
                    .thenReturn(new EtaResult("READY", "GOOGLE_ROUTES", now, now.plusMinutes(20), 1200, "ready"));
            when(nearbyParkingSearchService.findNearby(any()))
                    .thenReturn(new NearbyParkingSearchResult(
                            "NEW_TAIPEI_XINZHUANG",
                            25.0414214,
                            121.4647633,
                            500,
                            "STRAIGHT_LINE",
                            List.of(),
                            0,
                            0
                    ));
            when(publishedAvailabilitySnapshotService.findLatest(any(), any()))
                    .thenAnswer(invocation -> new PublishedAvailabilityView(
                            invocation.getArgument(0, DatasetCode.class),
                            "NO_CATALOG_RESULTS",
                            null,
                            null,
                            null,
                            Map.of(),
                            "No catalog records were found inside the query radius."
                    ));
            when(sourceRefreshCoordinationService.refresh(any(), eq(TriggerType.MANUAL_REFRESH)))
                    .thenAnswer(invocation -> SourceRefreshCoordinationService.SourceRefreshResult.failed(
                            invocation.getArgument(0, DatasetCode.class),
                            null,
                            null,
                            "not available",
                            true
                    ));
            service = new ParkingQueryService(
                    clock,
                    nearbyParkingSearchService,
                    publishedAvailabilitySnapshotService,
                    sourceRefreshCoordinationService,
                    googleEtaService,
                    new InMemoryParkingQueryStateStore(queryStateProperties),
                    queryStateProperties,
                    sourceMappingRepository
            );
        }
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
