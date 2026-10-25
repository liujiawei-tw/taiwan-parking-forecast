package parking.forecast.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import parking.forecast.domain.DatasetCode;

@ConfigurationProperties(prefix = "parking.catalog.ntpc.command")
@Validated
public class NtpcCatalogImportCommandProperties {

    private boolean enabled = false;

    private boolean confirmWrite = false;

    private boolean reviewedSubset = false;

    private String expectedManifestSha256 = "";

    @NotEmpty
    private List<DatasetCode> datasets = new ArrayList<>(List.of(
            DatasetCode.NTPC_ROADSIDE_SPOT,
            DatasetCode.NTPC_OFF_STREET_AVAILABILITY
    ));

    public boolean isReviewedSubset() { return reviewedSubset; }

    public void setReviewedSubset(boolean reviewedSubset) { this.reviewedSubset = reviewedSubset; }

    public String getExpectedManifestSha256() { return expectedManifestSha256; }

    public void setExpectedManifestSha256(String value) { this.expectedManifestSha256 = value; }

    public List<DatasetCode> getDatasets() { return List.copyOf(datasets); }

    public void setDatasets(List<DatasetCode> datasets) {
        this.datasets = datasets == null ? new ArrayList<>() : new ArrayList<>(datasets);
    }

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
