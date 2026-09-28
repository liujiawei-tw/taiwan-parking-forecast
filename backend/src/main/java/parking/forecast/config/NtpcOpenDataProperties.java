package parking.forecast.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "parking.sources.ntpc")
@Validated
public class NtpcOpenDataProperties {

    @NotNull
    private URI baseUrl = URI.create("https://data.ntpc.gov.tw");

    @NotNull
    private Duration timeout = Duration.ofSeconds(10);

    @Min(1)
    private int maxAttempts = 1;

    private boolean externalCallsEnabled = true;

    @Valid
    @NotNull
    private Dataset roadside = new Dataset("54A507C4-C038-41B5-BF60-BBECB9D052C6");

    @Valid
    @NotNull
    private Dataset offStreet = new Dataset("e09b35a5-a738-48cc-b0f5-570b67ad9c78");

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isExternalCallsEnabled() {
        return externalCallsEnabled;
    }

    public void setExternalCallsEnabled(boolean externalCallsEnabled) {
        this.externalCallsEnabled = externalCallsEnabled;
    }

    public Dataset getRoadside() {
        return roadside;
    }

    public void setRoadside(Dataset roadside) {
        this.roadside = roadside;
    }

    public Dataset getOffStreet() {
        return offStreet;
    }

    public void setOffStreet(Dataset offStreet) {
        this.offStreet = offStreet;
    }

    public static class Dataset {

        @NotBlank
        private String datasetId;

        @NotBlank
        private String jsonPath = "/api/datasets/{datasetId}/json";

        public Dataset() {
        }

        public Dataset(String datasetId) {
            this.datasetId = datasetId;
        }

        public String getDatasetId() {
            return datasetId;
        }

        public void setDatasetId(String datasetId) {
            this.datasetId = datasetId;
        }

        public String getJsonPath() {
            return jsonPath;
        }

        public void setJsonPath(String jsonPath) {
            this.jsonPath = jsonPath;
        }
    }
}
