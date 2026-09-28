package parking.forecast.adapter.ntpc;

import java.net.URI;
import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.config.NtpcOpenDataProperties;

@Component
public class NtpcOpenDataClient {

    private static final ParameterizedTypeReference<List<NtpcRoadsideParkingAvailabilityDto>> ROADSIDE_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<NtpcOffStreetParkingAvailabilityDto>> OFF_STREET_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final NtpcOpenDataProperties properties;

    public NtpcOpenDataClient(RestClient ntpcOpenDataRestClient, NtpcOpenDataProperties properties) {
        this.restClient = ntpcOpenDataRestClient;
        this.properties = properties;
    }

    public List<NtpcRoadsideParkingAvailabilityDto> fetchRoadsideAvailability(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getRoadside(),
                page,
                size,
                ROADSIDE_LIST,
                "NTPC roadside parking availability");
    }

    public List<NtpcOffStreetParkingAvailabilityDto> fetchOffStreetAvailability(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getOffStreet(),
                page,
                size,
                OFF_STREET_LIST,
                "NTPC off-street parking availability");
    }

    private <T> List<T> fetch(
            NtpcOpenDataProperties.Dataset dataset,
            int page,
            int size,
            ParameterizedTypeReference<List<T>> responseType,
            String description) {
        ensureExternalCallsEnabled(description);
        NtpcOpenDataClientException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                var body = restClient.get()
                        .uri(uriBuilder -> buildUri(uriBuilder, dataset, page, size))
                        .retrieve()
                        .body(responseType);
                return body == null ? List.of() : body;
            } catch (RestClientResponseException exception) {
                lastFailure = new NtpcOpenDataClientException(
                        "%s request failed with HTTP %s".formatted(description, exception.getStatusCode().value()),
                        exception);
            } catch (RestClientException exception) {
                lastFailure = new NtpcOpenDataClientException(
                        "%s request failed before a usable response was received".formatted(description),
                        exception);
            }
        }
        throw lastFailure == null
                ? new NtpcOpenDataClientException("%s request failed".formatted(description))
                : lastFailure;
    }

    private URI buildUri(UriBuilder uriBuilder, NtpcOpenDataProperties.Dataset dataset, int page, int size) {
        return uriBuilder
                .path(dataset.getJsonPath())
                .queryParam("page", page)
                .queryParam("size", size)
                .build(dataset.getDatasetId());
    }

    private void ensureExternalCallsEnabled(String description) {
        if (!properties.isExternalCallsEnabled()) {
            throw new NtpcOpenDataClientException(
                    "%s external calls are disabled by configuration".formatted(description));
        }
    }

    private void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than 0");
        }
    }
}


