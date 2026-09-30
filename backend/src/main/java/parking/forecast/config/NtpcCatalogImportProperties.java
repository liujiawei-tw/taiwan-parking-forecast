package parking.forecast.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "parking.catalog.ntpc")
@Validated
public class NtpcCatalogImportProperties {

    @Min(1)
    private int pageSize = 1000;

    @Min(1)
    private int maxPages = 50;

    private List<@Valid Scope> scopes = new ArrayList<>(List.of(
            new Scope("NEW_TAIPEI_XINZHUANG", "65000050", "新莊區")
    ));

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public List<Scope> getScopes() {
        return scopes;
    }

    public void setScopes(List<Scope> scopes) {
        this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
    }

    public static class Scope {

        @NotBlank
        private String serviceArea;

        @NotBlank
        private String roadsideAreaCode;

        @NotBlank
        private String offStreetAreaName;

        public Scope() {
        }

        public Scope(String serviceArea, String roadsideAreaCode, String offStreetAreaName) {
            this.serviceArea = serviceArea;
            this.roadsideAreaCode = roadsideAreaCode;
            this.offStreetAreaName = offStreetAreaName;
        }

        public String getServiceArea() {
            return serviceArea;
        }

        public void setServiceArea(String serviceArea) {
            this.serviceArea = serviceArea;
        }

        public String getRoadsideAreaCode() {
            return roadsideAreaCode;
        }

        public void setRoadsideAreaCode(String roadsideAreaCode) {
            this.roadsideAreaCode = roadsideAreaCode;
        }

        public String getOffStreetAreaName() {
            return offStreetAreaName;
        }

        public void setOffStreetAreaName(String offStreetAreaName) {
            this.offStreetAreaName = offStreetAreaName;
        }
    }
}
