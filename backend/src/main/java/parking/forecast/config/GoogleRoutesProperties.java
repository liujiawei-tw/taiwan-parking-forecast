package parking.forecast.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "parking.google.routes")
@Validated
public class GoogleRoutesProperties {

    @NotNull
    private URI baseUrl = URI.create("https://routes.googleapis.com");

    @NotBlank
    private String computeRoutesPath = "/directions/v2:computeRoutes";

    @NotNull
    private Duration timeout = Duration.ofSeconds(10);

    private boolean externalCallsEnabled = false;

    private String apiKey = "";

    @NotBlank
    private String travelMode = "DRIVE";

    @NotBlank
    private String routingPreference = "TRAFFIC_AWARE_OPTIMAL";

    @NotBlank
    private String fieldMask = "routes.duration,routes.staticDuration,routes.distanceMeters";

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getComputeRoutesPath() {
        return computeRoutesPath;
    }

    public void setComputeRoutesPath(String computeRoutesPath) {
        this.computeRoutesPath = computeRoutesPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isExternalCallsEnabled() {
        return externalCallsEnabled;
    }

    public void setExternalCallsEnabled(boolean externalCallsEnabled) {
        this.externalCallsEnabled = externalCallsEnabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTravelMode() {
        return travelMode;
    }

    public void setTravelMode(String travelMode) {
        this.travelMode = travelMode;
    }

    public String getRoutingPreference() {
        return routingPreference;
    }

    public void setRoutingPreference(String routingPreference) {
        this.routingPreference = routingPreference;
    }

    public String getFieldMask() {
        return fieldMask;
    }

    public void setFieldMask(String fieldMask) {
        this.fieldMask = fieldMask;
    }
}
