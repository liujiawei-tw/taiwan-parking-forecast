package parking.forecast.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "parking.catalog.ntpc.command")
@Validated
public class NtpcCatalogImportCommandProperties {

    private boolean enabled = false;

    private boolean confirmWrite = false;

    private boolean reviewedSubset = false;

    private String expectedManifestSha256 = "";

    public boolean isReviewedSubset() { return reviewedSubset; }

    public void setReviewedSubset(boolean reviewedSubset) { this.reviewedSubset = reviewedSubset; }

    public String getExpectedManifestSha256() { return expectedManifestSha256; }

    public void setExpectedManifestSha256(String value) { this.expectedManifestSha256 = value; }

    @NotBlank
    private String serviceArea = "NEW_TAIPEI_XINZHUANG";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isConfirmWrite() {
        return confirmWrite;
    }

    public void setConfirmWrite(boolean confirmWrite) {
        this.confirmWrite = confirmWrite;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public void setServiceArea(String serviceArea) {
        this.serviceArea = serviceArea;
    }
}
