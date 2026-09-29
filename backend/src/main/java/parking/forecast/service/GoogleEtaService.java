package parking.forecast.service;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import parking.forecast.adapter.google.GoogleRoutesClient;
import parking.forecast.adapter.google.GoogleRoutesClient.GoogleRoutesTravelTime;
import parking.forecast.adapter.google.GoogleRoutesClientException;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;

@Service
public class GoogleEtaService {

    private static final String PROVIDER = "GOOGLE_ROUTES";

    private final GoogleRoutesClient googleRoutesClient;

    public GoogleEtaService(GoogleRoutesClient googleRoutesClient) {
        this.googleRoutesClient = googleRoutesClient;
    }

    public EtaResult estimate(
            LocationInput origin,
            LocationInput destination,
            OffsetDateTime departureAt,
            boolean scheduledDeparture
    ) {
        try {
            GoogleRoutesTravelTime travelTime = googleRoutesClient.computeDrivingTravelTime(
                    origin.latitude(),
                    origin.longitude(),
                    destination.latitude(),
                    destination.longitude(),
                    scheduledDeparture ? departureAt : null
            );
            int durationSeconds = toDurationSeconds(travelTime.duration());
            return new EtaResult(
                    "READY",
                    PROVIDER,
                    departureAt,
                    departureAt.plusSeconds(durationSeconds),
                    durationSeconds,
                    "Google Routes traffic-aware ETA is ready."
            );
        } catch (GoogleRoutesClientException | ArithmeticException exception) {
            return new EtaResult(
                    "FAILED",
                    PROVIDER,
                    departureAt,
                    null,
                    null,
                    "Google Routes ETA is unavailable: " + exception.getMessage()
            );
        }
    }

    private int toDurationSeconds(Duration duration) {
        return Math.toIntExact(duration.toSeconds());
    }

    public record EtaResult(
            String status,
            String provider,
            OffsetDateTime departureAt,
            OffsetDateTime estimatedArrivalAt,
            Integer durationSeconds,
            String message) {
    }
}
