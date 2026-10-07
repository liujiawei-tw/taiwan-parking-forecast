package parking.forecast.adapter.ntpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingLotDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceSystem;

class NtpcCatalogRecordMapperTest {

    private static final String XINZHUANG_SCOPE = "NEW_TAIPEI_XINZHUANG";

    private final NtpcCatalogRecordMapper mapper = new NtpcCatalogRecordMapper();

    @Test
    void generalCarRulesDoNotInferEligibilityFromFeesOrDedicatedSpaces() {
        var records = mapper.toRoadsideCatalogRecords(XINZHUANG_SCOPE, "65000050", List.of(
                roadside("1", "1", "65000050", "路", "25", "121", "汽車停車位"),
                roadside("2", "2", "65000050", "路", "25", "121", "汽車身心障礙專用"),
                roadside("3", "3", "65000050", "路", "25", "121", "家長接送區"),
                roadside("4", "4", "65000050", "路", "25", "121", "時段性禁停停車位"),
                roadside("5", "5", "65000050", "路", "25", "121", "裝卸貨專用停車位"),
                roadside("6", "6", "65000050", "路", "25", "121", "大型車停車位"),
                roadside("7", "7", "65000050", "路", "25", "121", "未知汽車類型")
        ));
        assertThat(records).extracting(record -> record.carEligibleStatus()).containsExactly(
                EligibilityStatus.CONFIRMED, EligibilityStatus.NOT_ELIGIBLE,
                EligibilityStatus.UNKNOWN, EligibilityStatus.UNKNOWN, EligibilityStatus.UNKNOWN,
                EligibilityStatus.NOT_ELIGIBLE, EligibilityStatus.UNKNOWN);
        assertThat(records.get(3).rawLocationHint()).contains("memo=時段性禁停停車位", "day=1-5");
    }

    @Test
    void negativeOffstreetCapacityIsUnknownRatherThanNoCarSpaces() {
        var records = mapper.toOffStreetCatalogRecords(XINZHUANG_SCOPE, "新莊區", List.of(
                offStreet("P", "新莊區", "場", "路", "296239.5", "2770049", "-9")));
        assertThat(records.get(0).carEligibleStatus()).isEqualTo(EligibilityStatus.UNKNOWN);
    }

    @Test
    void mapsRoadsideRowsInsideConfiguredAreaToCatalogSourceRecords() {
        var records = mapper.toRoadsideCatalogRecords(
                XINZHUANG_SCOPE,
                "65000050",
                List.of(
                        roadside("NTPC-1", "CELL-1", "65000050", "新莊路", "25.0372", "121.4525", "小型車"),
                        roadside("", "CELL-2", "65000050", "中正路", "bad", "121.4510", ""),
                        roadside("NTPC-3", "CELL-3", "65000050", "化成路", "25.0410", "121.4530", "機車"),
                        roadside("NTPC-4", "CELL-4", "65000010", "板橋路", "25.0100", "121.4600", "小型車")
                ));

        assertThat(records).hasSize(3);
        assertThat(records.get(0).serviceArea()).isEqualTo(XINZHUANG_SCOPE);
        assertThat(records.get(0).sourceSystem()).isEqualTo(SourceSystem.NTPC_OPEN_DATA);
        assertThat(records.get(0).datasetCode()).isEqualTo(DatasetCode.NTPC_ROADSIDE_SPOT);
        assertThat(records.get(0).sourcePlaceId()).isEqualTo("NTPC-1");
        assertThat(records.get(0).placeType()).isEqualTo(ParkingPlaceType.ON_STREET_SPOT);
        assertThat(records.get(0).displayName()).isEqualTo("新莊路");
        assertThat(records.get(0).latitude()).isEqualByComparingTo(new BigDecimal("25.0372000"));
        assertThat(records.get(0).longitude()).isEqualByComparingTo(new BigDecimal("121.4525000"));
        assertThat(records.get(0).carEligibleStatus()).isEqualTo(EligibilityStatus.CONFIRMED);
        assertThat(records.get(1).sourcePlaceId()).isEqualTo("CELL-2");
        assertThat(records.get(1).latitude()).isNull();
        assertThat(records.get(1).carEligibleStatus()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(records.get(2).carEligibleStatus()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
    }

    @Test
    void mapsOffStreetCatalogRowsInsideConfiguredAreaAndConvertsTwd97Coordinates() {
        var records = mapper.toOffStreetCatalogRecords(
                XINZHUANG_SCOPE,
                "新莊區",
                List.of(
                        offStreet("050029", "新莊區", "新莊區農會停車場", "新北市新莊區中正路80號B2-B4", "296239.5", "2770049", "345"),
                        offStreet("050030", "新莊區", "無汽車位停車場", "新北市新莊區測試路1號", "bad", "2770049", "0"),
                        offStreet("990001", "板橋區", "板橋區測試停車場", "新北市板橋區測試路1號", "295000", "2765000", "120")
                ));

        assertThat(records).hasSize(2);
        assertThat(records.get(0).sourceSystem()).isEqualTo(SourceSystem.NTPC_OPEN_DATA);
        assertThat(records.get(0).datasetCode()).isEqualTo(DatasetCode.NTPC_OFF_STREET_AVAILABILITY);
        assertThat(records.get(0).sourcePlaceId()).isEqualTo("050029");
        assertThat(records.get(0).placeType()).isEqualTo(ParkingPlaceType.OFF_STREET_LOT);
        assertThat(records.get(0).displayName()).isEqualTo("新莊區農會停車場");
        assertThat(records.get(0).latitude().doubleValue()).isCloseTo(25.03786, org.assertj.core.data.Offset.offset(0.0002));
        assertThat(records.get(0).longitude().doubleValue()).isCloseTo(121.45823, org.assertj.core.data.Offset.offset(0.0002));
        assertThat(records.get(0).carEligibleStatus()).isEqualTo(EligibilityStatus.CONFIRMED);
        assertThat(records.get(0).rawLocationHint()).contains("tw97x=296239.5", "totalCar=345");
        assertThat(records.get(1).latitude()).isNull();
        assertThat(records.get(1).longitude()).isNull();
        assertThat(records.get(1).carEligibleStatus()).isEqualTo(EligibilityStatus.NOT_ELIGIBLE);
    }

    private NtpcRoadsideParkingAvailabilityDto roadside(
            String id,
            String cellId,
            String areaCode,
            String roadName,
            String latitude,
            String longitude,
            String eligibilityText
    ) {
        return new NtpcRoadsideParkingAvailabilityDto(
                id,
                cellId,
                eligibilityText,
                "1-5",
                "08-20",
                "",
                eligibilityText,
                eligibilityText,
                "R-1",
                roadName,
                "0",
                "Y",
                "0",
                latitude,
                longitude,
                "65000",
                areaCode
        );
    }

    private NtpcOffStreetParkingLotDto offStreet(
            String id,
            String area,
            String name,
            String address,
            String tw97x,
            String tw97y,
            String totalCar
    ) {
        return new NtpcOffStreetParkingLotDto(id, area, name, address, tw97x, tw97y, totalCar);
    }
}
