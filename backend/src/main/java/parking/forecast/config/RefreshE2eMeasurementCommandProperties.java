package parking.forecast.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import parking.forecast.domain.DatasetCode;

@Component
@ConfigurationProperties(prefix = "parking.refresh.measurement.command")
@Validated
public class RefreshE2eMeasurementCommandProperties {

    private boolean enabled = false;

    private List<DatasetCode> datasets = new ArrayList<>(List.of(
            DatasetCode.NTPC_ROADSIDE_SPOT,
            DatasetCode.NTPC_OFF_STREET_AVAILABILITY
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<DatasetCode> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<DatasetCode> datasets) {
        this.datasets = datasets == null ? new ArrayList<>() : new ArrayList<>(datasets);
    }
}
