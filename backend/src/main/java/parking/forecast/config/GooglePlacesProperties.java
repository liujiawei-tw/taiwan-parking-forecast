package parking.forecast.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "parking.google.places")
@Validated
public class GooglePlacesProperties {

    @NotNull
    private URI baseUrl = URI.create("https://places.googleapis.com");

    private String searchNearbyPath = "/v1/places:searchNearby";

    @NotNull
    private Duration timeout = Duration.ofSeconds(10);

    private boolean externalCallsEnabled;

    private String apiKey = "";

    @Min(1)
    @Max(20)
    private int maxResultCount = 20;

    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public String getSearchNearbyPath() { return searchNearbyPath; }
    public void setSearchNearbyPath(String searchNearbyPath) { this.searchNearbyPath = searchNearbyPath; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public boolean isExternalCallsEnabled() { return externalCallsEnabled; }
    public void setExternalCallsEnabled(boolean externalCallsEnabled) { this.externalCallsEnabled = externalCallsEnabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getMaxResultCount() { return maxResultCount; }
    public void setMaxResultCount(int maxResultCount) { this.maxResultCount = maxResultCount; }
}
