package parking.forecast.adapter.ntpc.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NtpcOffStreetParkingLotDto(
        @JsonAlias({ "ID", "id" }) String id,
        @JsonAlias({ "AREA", "area" }) String area,
        @JsonAlias({ "NAME", "name" }) String name,
        @JsonAlias({ "TYPE", "type" }) String type,
        @JsonAlias({ "SUMMARY", "summary" }) String summary,
        @JsonAlias({ "ADDRESS", "address" }) String address,
        @JsonAlias({ "PAYEX", "payex" }) String payEx,
        @JsonAlias({ "SERVICETIME", "servicetime" }) String serviceTime,
        @JsonAlias({ "TW97X", "tw97x" }) String tw97x,
        @JsonAlias({ "TW97Y", "tw97y" }) String tw97y,
        @JsonAlias({ "TOTALCAR", "totalcar" }) String totalCar) {

    public NtpcOffStreetParkingLotDto(
            String id,
            String area,
            String name,
            String address,
            String tw97x,
            String tw97y,
            String totalCar
    ) {
        this(id, area, name, null, null, address, null, null, tw97x, tw97y, totalCar);
    }
}
