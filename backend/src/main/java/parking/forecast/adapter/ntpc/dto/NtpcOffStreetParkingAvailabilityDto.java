package parking.forecast.adapter.ntpc.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NtpcOffStreetParkingAvailabilityDto(
        @JsonAlias({ "ID", "id" }) String id,
        @JsonAlias({ "AVAILABLECAR", "AVAILABLE", "availablecar", "available" }) String availableCar) {
}
