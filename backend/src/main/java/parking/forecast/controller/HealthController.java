package parking.forecast.controller;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final Clock clock;

    public HealthController() {
        this(Clock.systemUTC());
    }

    HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "taiwan-parking-forecast-api", OffsetDateTime.now(clock));
    }

    public record HealthResponse(String status, String service, OffsetDateTime serverTime) {
    }
}