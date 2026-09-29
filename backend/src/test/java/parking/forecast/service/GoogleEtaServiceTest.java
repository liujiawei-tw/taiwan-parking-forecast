package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import parking.forecast.adapter.google.GoogleRoutesClient;
import parking.forecast.adapter.google.GoogleRoutesClient.GoogleRoutesTravelTime;
import parking.forecast.adapter.google.GoogleRoutesClientException;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;

@ExtendWith(MockitoExtension.class)
class GoogleEtaServiceTest {

    @Mock
    private GoogleRoutesClient googleRoutesClient;

    @Test
    void returnsReadyEtaFromGoogleRoutesDuration() {
        var departureAt = OffsetDateTime.parse("2026-09-29T10:00:00+08:00");
        when(googleRoutesClient.computeDrivingTravelTime(25.04, 121.45, 25.0372, 121.4525, null))
                .thenReturn(new GoogleRoutesTravelTime(Duration.ofSeconds(481), Duration.ofSeconds(420), 1300));
        var service = new GoogleEtaService(googleRoutesClient);

        var eta = service.estimate(origin(), destination(), departureAt, false);

        assertThat(eta.status()).isEqualTo("READY");
        assertThat(eta.provider()).isEqualTo("GOOGLE_ROUTES");
        assertThat(eta.departureAt()).isEqualTo(departureAt);
        assertThat(eta.estimatedArrivalAt()).isEqualTo(departureAt.plusSeconds(481));
        assertThat(eta.durationSeconds()).isEqualTo(481);
        verify(googleRoutesClient).computeDrivingTravelTime(25.04, 121.45, 25.0372, 121.4525, null);
    }

    @Test
    void sendsScheduledDepartureTimeToGoogleRoutes() {
        var departureAt = OffsetDateTime.parse("2026-09-29T10:00:00+08:00");
        when(googleRoutesClient.computeDrivingTravelTime(25.04, 121.45, 25.0372, 121.4525, departureAt))
                .thenReturn(new GoogleRoutesTravelTime(Duration.ofSeconds(481), Duration.ofSeconds(420), 1300));
        var service = new GoogleEtaService(googleRoutesClient);

        var eta = service.estimate(origin(), destination(), departureAt, true);

        assertThat(eta.status()).isEqualTo("READY");
        assertThat(eta.estimatedArrivalAt()).isEqualTo(departureAt.plusSeconds(481));
        verify(googleRoutesClient).computeDrivingTravelTime(25.04, 121.45, 25.0372, 121.4525, departureAt);
    }

    @Test
    void returnsFailedEtaWithoutArrivalWhenGoogleRoutesFails() {
        var departureAt = OffsetDateTime.parse("2026-09-29T10:00:00+08:00");
        when(googleRoutesClient.computeDrivingTravelTime(25.04, 121.45, 25.0372, 121.4525, null))
                .thenThrow(new GoogleRoutesClientException("Google Routes external calls are disabled by configuration"));
        var service = new GoogleEtaService(googleRoutesClient);

        var eta = service.estimate(origin(), destination(), departureAt, false);

        assertThat(eta.status()).isEqualTo("FAILED");
        assertThat(eta.provider()).isEqualTo("GOOGLE_ROUTES");
        assertThat(eta.departureAt()).isEqualTo(departureAt);
        assertThat(eta.estimatedArrivalAt()).isNull();
        assertThat(eta.durationSeconds()).isNull();
        assertThat(eta.message()).contains("disabled");
    }

    private LocationInput origin() {
        return new LocationInput("目前位置", 25.04, 121.45);
    }

    private LocationInput destination() {
        return new LocationInput("新莊體育館", 25.0372, 121.4525);
    }
}
