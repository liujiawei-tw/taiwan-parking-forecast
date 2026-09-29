package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import parking.forecast.config.ParkingQueryStateProperties;
import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;
import parking.forecast.domain.DatasetCode;
import parking.forecast.service.GoogleEtaService.EtaResult;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;
import parking.forecast.service.ParkingQueryService.ParkingQueryExpiredException;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedAvailabilityView;

class ParkingQueryServiceTest {

    @Test
    void findByIdRejectsExpiredQuery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T01:00:00Z"));
        NearbyParkingSearchService nearbyParkingSearchService = mock(NearbyParkingSearchService.class);
        PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService = mock(PublishedAvailabilitySnapshotService.class);
        SourceRefreshCoordinationService sourceRefreshCoordinationService = mock(SourceRefreshCoordinationService.class);
        GoogleEtaService googleEtaService = mock(GoogleEtaService.class);
        ParkingQueryStateProperties queryStateProperties = new ParkingQueryStateProperties();
        ParkingQueryService service = new ParkingQueryService(
                clock,
                nearbyParkingSearchService,
                publishedAvailabilitySnapshotService,
                sourceRefreshCoordinationService,
                googleEtaService,
                new InMemoryParkingQueryStateStore(queryStateProperties),
                queryStateProperties
        );
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        when(googleEtaService.estimate(any(), any(), any(), anyBoolean()))
                .thenReturn(new EtaResult("READY", "GOOGLE_ROUTES", now, now.plusMinutes(20), 1200, "ready"));
        when(nearbyParkingSearchService.findNearby(any()))
                .thenReturn(new NearbyParkingSearchResult(
                        "NEW_TAIPEI_XINZHUANG",
                        25.0414214,
                        121.4647633,
                        300,
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
                        Map.of(),
                        "No catalog records were found inside the query radius."
                ));

        var created = service.create(mockRequestNearSeedCatalog());
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.findById(created.query().queryId()))
                .isInstanceOf(ParkingQueryExpiredException.class);
    }

    private CreateParkingQueryRequest mockRequestNearSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-" + UUID.randomUUID(),
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("IKEA 新莊", 25.0414214, 121.4647633),
                new DepartureInput("NOW", null));
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
