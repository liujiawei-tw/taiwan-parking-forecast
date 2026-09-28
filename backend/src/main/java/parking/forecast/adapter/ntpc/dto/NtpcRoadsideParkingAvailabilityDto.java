package parking.forecast.adapter.ntpc.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NtpcRoadsideParkingAvailabilityDto(
        @JsonAlias({ "id", "ID" }) String id,
        @JsonAlias({ "cellid", "CELLID" }) String cellId,
        @JsonAlias({ "name", "NAME" }) String name,
        @JsonAlias({ "day", "DAY" }) String day,
        @JsonAlias({ "hour", "HOUR" }) String hour,
        @JsonAlias({ "pay", "PAY" }) String pay,
        @JsonAlias({ "paycash", "PAYCASH" }) String payCash,
        @JsonAlias({ "memo", "MEMO" }) String memo,
        @JsonAlias({ "roadid", "ROADID" }) String roadId,
        @JsonAlias({ "roadname", "ROADNAME" }) String roadName,
        @JsonAlias({ "cellstatus", "CELLSTATUS" }) String cellStatus,
        @JsonAlias({ "isnowcash", "ISNOWCASH" }) String isNowCash,
        @JsonAlias({ "parkingstatus", "PARKINGSTATUS" }) String parkingStatus,
        @JsonAlias({ "latitude", "LATITUDE" }) String latitude,
        @JsonAlias({ "longitude", "LONGITUDE" }) String longitude,
        @JsonAlias({ "countycode", "COUNTYCODE" }) String countyCode,
        @JsonAlias({ "areacode", "AREACODE" }) String areaCode) {
}
