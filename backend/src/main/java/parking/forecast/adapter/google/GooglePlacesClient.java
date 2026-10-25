package parking.forecast.adapter.google;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import parking.forecast.adapter.google.dto.SearchNearbyRequest;
import parking.forecast.adapter.google.dto.SearchNearbyResponse;
import parking.forecast.config.GooglePlacesProperties;

@Component
public class GooglePlacesClient {

    private static final String API_KEY_HEADER = "X-Goog-Api-Key";
    private static final String FIELD_MASK_HEADER = "X-Goog-FieldMask";
    private static final String FIELD_MASK = "places.id,places.displayName,places.location,places.formattedAddress,places.googleMapsUri,places.navigationPoints";
    private static final List<String> PARKING_TYPES = List.of("parking", "parking_lot", "parking_garage");

    private final RestClient restClient;
    private final GooglePlacesProperties properties;

    public GooglePlacesClient(
            @Qualifier("googlePlacesRestClient") RestClient restClient,
            GooglePlacesProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public SearchNearbyResponse searchParkingFacilities(double latitude, double longitude, int radiusMeters) {
        if (!properties.isExternalCallsEnabled()) {
            throw new GooglePlacesClientException("Google Places nearby search is disabled by configuration", "DISABLED");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new GooglePlacesClientException("Google Places server API key is not configured", "NOT_CONFIGURED");
        }

        SearchNearbyRequest request = new SearchNearbyRequest(
                PARKING_TYPES,
                properties.getMaxResultCount(),
                "zh-TW",
                "TW",
                new SearchNearbyRequest.LocationRestriction(
                        new SearchNearbyRequest.Circle(
                                new SearchNearbyRequest.Center(latitude, longitude), radiusMeters)),
                "DISTANCE");

        try {
            SearchNearbyResponse response = restClient.post()
                    .uri(properties.getSearchNearbyPath())
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header(FIELD_MASK_HEADER, FIELD_MASK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(request)
                    .retrieve()
                    .body(SearchNearbyResponse.class);
            return response == null ? new SearchNearbyResponse(List.of()) : response;
        } catch (RestClientResponseException exception) {
            throw new GooglePlacesClientException(
                    "Google Places nearby search failed with HTTP %s".formatted(exception.getStatusCode().value()),
                    exception);
        } catch (RestClientException exception) {
            throw new GooglePlacesClientException(
                    "Google Places nearby search failed before a usable response was received",
                    exception);
        }
    }
}
