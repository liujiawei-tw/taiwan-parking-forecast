package tw.jiawei.parking.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthReturnsApiStatusAndServerTime() {
        var fixedClock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);
        var controller = new HealthController(fixedClock);

        var response = controller.health();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("taiwan-parking-forecast-api");
        assertThat(response.serverTime().toInstant()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
    }
}