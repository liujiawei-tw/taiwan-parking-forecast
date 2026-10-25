package parking.forecast.adapter.ntpc.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NtpcRoadsideFeeRateDto(
        @JsonAlias({"county", "COUNTY"}) String county,
        @JsonAlias({"countycode", "COUNTYCODE"}) String countyCode,
        @JsonAlias({"area", "AREA"}) String area,
        @JsonAlias({"areacode", "AREACODE"}) String areaCode,
        @JsonAlias({"road_name", "ROAD_NAME"}) String roadName,
        @JsonAlias({"weekdays_time", "WEEKDAYS_TIME"}) String weekdaysTime,
        @JsonAlias({"sat._charging_time", "SAT._CHARGING_TIME"}) String saturdayChargingTime,
        @JsonAlias({"sun._charging_time", "SUN._CHARGING_TIME"}) String sundayChargingTime,
        @JsonAlias({"national_holidays_charging_time", "NATIONAL_HOLIDAYS_CHARGING_TIME"}) String nationalHolidaysChargingTime,
        @JsonAlias({"rates", "RATES"}) String rates) {
}
