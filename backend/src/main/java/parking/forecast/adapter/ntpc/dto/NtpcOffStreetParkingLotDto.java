package parking.forecast.adapter.ntpc.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NtpcOffStreetParkingLotDto(
        @JsonAlias({ "ID", "id" }) String id,
        @JsonAlias({ "AREA", "area" }) String area,
        @JsonAlias({ "NAME", "name" }) String name,
        @JsonAlias({ "ADDRESS", "address" }) String address,
        @JsonAlias({ "TW97X", "tw97x" }) String tw97x,
        @JsonAlias({ "TW97Y", "tw97y" }) String tw97y,
        @JsonAlias({ "TOTALCAR", "totalcar" }) String totalCar) {
}
