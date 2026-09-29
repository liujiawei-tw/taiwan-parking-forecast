package parking.forecast.adapter.google;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import parking.forecast.adapter.google.dto.ComputeRoutesRequest;
import parking.forecast.adapter.google.dto.ComputeRoutesResponse;
import parking.forecast.config.GoogleRoutesProperties;

@Component
public class GoogleRoutesClient {

    private static final String API_KEY_HEADER = "X-Goog-Api-Key";
    private static final String FIELD_MASK_HEADER = "X-Goog-FieldMask";

    private final RestClient restClient;
    private final GoogleRoutesProperties properties;

    public GoogleRoutesClient(
            @Qualifier("googleRoutesRestClient") RestClient restClient,
            GoogleRoutesProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public GoogleRoutesTravelTime computeDrivingTravelTime(
            double originLatitude,
            double originLongitude,
            double destinationLatitude,
            double destinationLongitude,
            OffsetDateTime departureAt
    ) {
        ensureExternalCallsEnabled();
        ensureApiKeyConfigured();
        ComputeRoutesRequest request = new ComputeRoutesRequest(
                ComputeRoutesRequest.RouteWaypoint.of(originLatitude, originLongitude),
                ComputeRoutesRequest.RouteWaypoint.of(destinationLatitude, destinationLongitude),
                properties.getTravelMode(),
                properties.getRoutingPreference(),
                departureAt.toInstant().toString()
        );

        try {
            ComputeRoutesResponse response = restClient.post()
                    .uri(properties.getComputeRoutesPath())
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header(FIELD_MASK_HEADER, properties.getFieldMask())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(request)
                    .retrieve()
                    .body(ComputeRoutesResponse.class);
            return toTravelTime(response);
        } catch (RestClientResponseException exception) {
            throw new GoogleRoutesClientException(
                    "Google Routes request failed with HTTP %s".formatted(exception.getStatusCode().value()),
                    exception);
        } catch (RestClientException exception) {
            throw new GoogleRoutesClientException(
                    "Google Routes request failed before a usable response was received",
                    exception);
        }
    }

    private GoogleRoutesTravelTime toTravelTime(ComputeRoutesResponse response) {
        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            throw new GoogleRoutesClientException("Google Routes returned no routes");
        }
        ComputeRoutesResponse.Route route = response.routes().getFirst();
        return new GoogleRoutesTravelTime(
                parseGoogleDuration(route.duration(), "duration"),
                parseOptionalGoogleDuration(route.staticDuration(), "staticDuration"),
                route.distanceMeters()
        );
    }

    private Duration parseOptionalGoogleDuration(String value, String fieldName) {
        return StringUtils.hasText(value) ? parseGoogleDuration(value, fieldName) : null;
    }

    private Duration parseGoogleDuration(String value, String fieldName) {
        if (!StringUtils.hasText(value) || !value.endsWith("s")) {
            throw new GoogleRoutesClientException("Google Routes returned invalid %s".formatted(fieldName));
        }
        try {
            BigDecimal seconds = new BigDecimal(value.substring(0, value.length() - 1));
            BigDecimal nanos = seconds.multiply(BigDecimal.valueOf(1_000_000_000L));
            return Duration.ofNanos(nanos.setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new GoogleRoutesClientException("Google Routes returned invalid %s".formatted(fieldName), exception);
        }
    }

    private void ensureExternalCallsEnabled() {
        if (!properties.isExternalCallsEnabled()) {
            throw new GoogleRoutesClientException("Google Routes external calls are disabled by configuration");
        }
    }

    private void ensureApiKeyConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new GoogleRoutesClientException("Google Routes API key is not configured");
        }
    }

    public record GoogleRoutesTravelTime(
            Duration duration,
            Duration staticDuration,
            Integer distanceMeters) {
    }
}
