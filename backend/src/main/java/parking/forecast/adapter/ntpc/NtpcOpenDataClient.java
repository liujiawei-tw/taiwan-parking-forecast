package parking.forecast.adapter.ntpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingLotDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideFeeRateDto;
import parking.forecast.config.NtpcOpenDataProperties;

@Component
public class NtpcOpenDataClient {

    private final RestClient restClient;
    private final NtpcOpenDataProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public NtpcOpenDataClient(
            @Qualifier("ntpcOpenDataRestClient") RestClient ntpcOpenDataRestClient,
            NtpcOpenDataProperties properties
    ) {
        this(ntpcOpenDataRestClient, properties, new ObjectMapper());
    }

    NtpcOpenDataClient(RestClient ntpcOpenDataRestClient, NtpcOpenDataProperties properties, ObjectMapper objectMapper) {
        this.restClient = ntpcOpenDataRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<NtpcRoadsideParkingAvailabilityDto> fetchRoadsideAvailability(int page, int size) {
        return fetchRoadsideAvailabilityPage(page, size).rows();
    }

    public NtpcOpenDataPage<NtpcRoadsideParkingAvailabilityDto> fetchRoadsideAvailabilityPage(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getRoadside(),
                page,
                size,
                NtpcRoadsideParkingAvailabilityDto.class,
                "NTPC roadside parking availability");
    }

    public NtpcOpenDataPage<NtpcRoadsideFeeRateDto> fetchRoadsideFeeRatesPage(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getRoadsideFeeRates(),
                page,
                size,
                NtpcRoadsideFeeRateDto.class,
                "NTPC roadside parking fee rates");
    }

    public List<NtpcOffStreetParkingAvailabilityDto> fetchOffStreetAvailability(int page, int size) {
        return fetchOffStreetAvailabilityPage(page, size).rows();
    }

    public NtpcOpenDataPage<NtpcOffStreetParkingAvailabilityDto> fetchOffStreetAvailabilityPage(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getOffStreet(),
                page,
                size,
                NtpcOffStreetParkingAvailabilityDto.class,
                "NTPC off-street parking availability");
    }

    public List<NtpcOffStreetParkingLotDto> fetchOffStreetCatalog(int page, int size) {
        return fetchOffStreetCatalogPage(page, size).rows();
    }

    public NtpcOpenDataPage<NtpcOffStreetParkingLotDto> fetchOffStreetCatalogPage(int page, int size) {
        validatePageRequest(page, size);
        return fetch(
                properties.getOffStreetCatalog(),
                page,
                size,
                NtpcOffStreetParkingLotDto.class,
                "NTPC off-street parking catalog");
    }

    private <T> NtpcOpenDataPage<T> fetch(
            NtpcOpenDataProperties.Dataset dataset,
            int page,
            int size,
            Class<T> rowType,
            String description) {
        ensureExternalCallsEnabled(description);
        NtpcOpenDataClientException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            long startedNanos = System.nanoTime();
            try {
                String body = restClient.get()
                        .uri(uriBuilder -> buildUri(uriBuilder, dataset, page, size))
                        .retrieve()
                        .body(String.class);
                long elapsedMillis = elapsedMillis(startedNanos);
                long responseBytes = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
                return new NtpcOpenDataPage<>(
                        bodyToRows(body, rowType, description),
                        page,
                        size,
                        attempt,
                        responseBytes,
                        elapsedMillis
                );
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

    private <T> List<T> bodyToRows(String body, Class<T> rowType, String description) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            var listType = objectMapper.getTypeFactory().constructCollectionType(List.class, rowType);
            List<T> rows = objectMapper.readValue(body, listType);
            return rows == null ? List.of() : rows;
        } catch (JsonProcessingException exception) {
            throw new NtpcOpenDataClientException(
                    "%s response body could not be parsed as JSON array".formatted(description),
                    exception
            );
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
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

    public record NtpcOpenDataPage<T>(
            List<T> rows,
            int page,
            int size,
            int attempts,
            long responseBytes,
            long elapsedMillis
    ) {
        public NtpcOpenDataPage {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}


