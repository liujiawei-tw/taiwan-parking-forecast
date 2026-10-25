package parking.forecast.adapter.ntpc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingLotDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceSystem;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;

@Component
public class NtpcCatalogRecordMapper {

    private static final int COORDINATE_SCALE = 7;
    private static final String XINZHUANG_SPORTS_PARK_SOURCE_ID = "050064";
    private static final String XINZHUANG_SPORTS_PARK_DISPLAY_NAME =
            "新莊體育園區停車場（園區合計；含正好停-新莊體育館地下停車場、公園路平面區）";
    private static final String XINZHUANG_SPORTS_PARK_CATALOG_NOTE =
            "官方來源 050064 回報園區合計 706 個汽車位；體育局委外資料分列網球場地下 189、體育館地下 399、臨復興路 37、臨公園路 32、臨中華路 49 格。官方來源沒有各區獨立 ID 或分區即時剩餘位，故維持單一場站，避免重複計算。使用者提及的「公園路 D 區」是否即為臨公園路區，官方資料尚未確認。";
    private static final Map<String, ReviewedOffStreetOverride> REVIEWED_OFF_STREET_OVERRIDES = Map.of(
            XINZHUANG_SPORTS_PARK_SOURCE_ID,
            new ReviewedOffStreetOverride(
                    XINZHUANG_SPORTS_PARK_DISPLAY_NAME,
                    new Wgs84Coordinate(new BigDecimal("25.0405039"), new BigDecimal("121.4519880")),
                    XINZHUANG_SPORTS_PARK_CATALOG_NOTE
            )
    );

    public List<CatalogSourceRecord> toRoadsideCatalogRecords(
            String serviceArea,
            String roadsideAreaCode,
            List<NtpcRoadsideParkingAvailabilityDto> rows
    ) {
        return rows.stream()
                .filter(row -> matches(roadsideAreaCode, row.areaCode()))
                .map(row -> toRoadsideCatalogRecord(serviceArea, row))
                .toList();
    }

    public List<CatalogSourceRecord> toOffStreetCatalogRecords(
            String serviceArea,
            String offStreetAreaName,
            List<NtpcOffStreetParkingLotDto> rows
    ) {
        return rows.stream()
                .filter(row -> matches(offStreetAreaName, row.area()))
                .map(row -> toOffStreetCatalogRecord(serviceArea, row))
                .toList();
    }

    private CatalogSourceRecord toRoadsideCatalogRecord(String serviceArea, NtpcRoadsideParkingAvailabilityDto row) {
        return new CatalogSourceRecord(
                serviceArea,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                firstNonBlank(row.id(), row.cellId()),
                ParkingPlaceType.ON_STREET_SPOT,
                normalizeAndLimitText(firstNonBlank(row.roadName(), row.name(), fallback("路邊格位", row.cellId())), 200),
                parseCoordinate(row.latitude()),
                parseCoordinate(row.longitude()),
                NtpcRoadsideCarEligibility.classify(row),
                "roadName=%s; cellId=%s; roadId=%s".formatted(
                        nullToEmpty(row.roadName()),
                        nullToEmpty(row.cellId()),
                        nullToEmpty(row.roadId())),
                "areaCode=%s; name=%s; latitude=%s; longitude=%s; memo=%s; day=%s; hour=%s".formatted(
                        nullToEmpty(row.areaCode()),
                        nullToEmpty(row.name()),
                        nullToEmpty(row.latitude()),
                        nullToEmpty(row.longitude()),
                        nullToEmpty(row.memo()),
                        nullToEmpty(row.day()),
                        nullToEmpty(row.hour())),
                null,
                normalizeAndLimitText(row.name(), 120),
                normalizeAndLimitText(row.pay(), 120),
                normalizeAndLimitText(row.payCash(), 1000),
                normalizeAndLimitText(row.day(), 250),
                normalizeAndLimitText(row.hour(), 250),
                trimToNull(row.cellId()) == null ? null : 1,
                normalizeAndLimitText(row.memo(), 2000)
        );
    }

    private CatalogSourceRecord toOffStreetCatalogRecord(String serviceArea, NtpcOffStreetParkingLotDto row) {
        String sourcePlaceId = trimToNull(row.id());
        var coordinate = twd97ToWgs84(row.tw97x(), row.tw97y());
        var override = sourcePlaceId == null ? null : REVIEWED_OFF_STREET_OVERRIDES.get(sourcePlaceId);
        boolean coordinateWasOverridden = coordinate == null && override != null;
        if (coordinateWasOverridden) {
            coordinate = override.coordinate();
        }
        String displayName = override == null
                ? normalizeAndLimitText(firstNonBlank(row.name(), fallback("路外停車場", row.id())), 200)
                : normalizeAndLimitText(override.displayName(), 200);
        return new CatalogSourceRecord(
                serviceArea,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                sourcePlaceId,
                ParkingPlaceType.OFF_STREET_LOT,
                displayName,
                coordinate == null ? null : coordinate.latitude(),
                coordinate == null ? null : coordinate.longitude(),
                inferOffStreetCarEligibility(row.totalCar()),
                "name=%s; area=%s".formatted(nullToEmpty(row.name()), nullToEmpty(row.area())),
                "address=%s; tw97x=%s; tw97y=%s; totalCar=%s".formatted(
                        nullToEmpty(row.address()),
                        nullToEmpty(row.tw97x()),
                        nullToEmpty(row.tw97y()),
                        nullToEmpty(row.totalCar())),
                normalizeAndLimitText(row.address(), 500),
                normalizeAndLimitText(row.type(), 120),
                null,
                normalizeAndLimitText(row.payEx(), 1000),
                null,
                normalizeAndLimitText(row.serviceTime(), 250),
                parseNonNegativeInteger(row.totalCar()),
                offStreetCatalogNotes(row.summary(), override, coordinateWasOverridden)
        );
    }

    private String offStreetCatalogNotes(
            String sourceSummary,
            ReviewedOffStreetOverride override,
            boolean coordinateWasOverridden
    ) {
        if (override == null) {
            return normalizeAndLimitText(sourceSummary, 2000);
        }
        String correctionNote = coordinateWasOverridden
                ? " 官方 TW97X/TW97Y 為 0/0，座標改採新北市門牌位置服務對中華路一段75號的地址點；此為場址地址點，非分區入口的現地測量。"
                : "";
        String sourceNote = trimToNull(sourceSummary);
        return normalizeAndLimitText(
                override.catalogNote() + correctionNote + (sourceNote == null ? "" : " 官方摘要：" + sourceNote),
                2000
        );
    }

    private Integer parseNonNegativeInteger(String value) {
        var trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private EligibilityStatus inferOffStreetCarEligibility(String totalCarValue) {
        var totalCar = trimToNull(totalCarValue);
        if (totalCar == null) {
            return EligibilityStatus.UNKNOWN;
        }
        try {
            int capacity = Integer.parseInt(totalCar);
            return capacity > 0 ? EligibilityStatus.CONFIRMED
                    : capacity == 0 ? EligibilityStatus.NOT_ELIGIBLE : EligibilityStatus.UNKNOWN;
        } catch (NumberFormatException exception) {
            return EligibilityStatus.UNKNOWN;
        }
    }

    private BigDecimal parseCoordinate(String value) {
        var trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(trimmed)).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Wgs84Coordinate twd97ToWgs84(String tw97x, String tw97y) {
        var xValue = trimToNull(tw97x);
        var yValue = trimToNull(tw97y);
        if (xValue == null || yValue == null) {
            return null;
        }

        try {
            double x = Double.parseDouble(xValue);
            double y = Double.parseDouble(yValue);
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || x < 100_000 || x > 400_000
                    || y < 2_300_000 || y > 2_900_000) {
                return null;
            }
            Wgs84Coordinate coordinate = inverseTaiwanTm2(x, y);
            return isTaiwanCoordinate(coordinate) ? coordinate : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isTaiwanCoordinate(Wgs84Coordinate coordinate) {
        return coordinate != null
                && coordinate.latitude().compareTo(BigDecimal.valueOf(20)) > 0
                && coordinate.latitude().compareTo(BigDecimal.valueOf(27)) < 0
                && coordinate.longitude().compareTo(BigDecimal.valueOf(118)) > 0
                && coordinate.longitude().compareTo(BigDecimal.valueOf(123)) < 0;
    }

    private Wgs84Coordinate inverseTaiwanTm2(double rawX, double rawY) {
        double semiMajorAxis = 6378137.0;
        double semiMinorAxis = 6356752.314245;
        double centralMeridian = Math.toRadians(121);
        double scaleFactor = 0.9999;
        double falseEasting = 250000;

        double eccentricity = Math.sqrt(1 - Math.pow(semiMinorAxis, 2) / Math.pow(semiMajorAxis, 2));
        double x = rawX - falseEasting;
        double y = rawY;
        double meridianArc = y / scaleFactor;
        double mu = meridianArc / (semiMajorAxis * (1 - Math.pow(eccentricity, 2) / 4
                - 3 * Math.pow(eccentricity, 4) / 64
                - 5 * Math.pow(eccentricity, 6) / 256));

        double e1 = (1 - Math.sqrt(1 - Math.pow(eccentricity, 2)))
                / (1 + Math.sqrt(1 - Math.pow(eccentricity, 2)));
        double footpointLatitude = mu
                + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * Math.pow(e1, 2) / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);

        double secondEccentricitySquared = Math.pow(eccentricity, 2) / (1 - Math.pow(eccentricity, 2));
        double sinFootpoint = Math.sin(footpointLatitude);
        double cosFootpoint = Math.cos(footpointLatitude);
        double tanFootpoint = Math.tan(footpointLatitude);
        double c1 = secondEccentricitySquared * Math.pow(cosFootpoint, 2);
        double t1 = Math.pow(tanFootpoint, 2);
        double radiusOfCurvature = semiMajorAxis * (1 - Math.pow(eccentricity, 2))
                / Math.pow(1 - Math.pow(eccentricity, 2) * Math.pow(sinFootpoint, 2), 1.5);
        double primeVerticalRadius = semiMajorAxis
                / Math.sqrt(1 - Math.pow(eccentricity, 2) * Math.pow(sinFootpoint, 2));
        double d = x / (primeVerticalRadius * scaleFactor);

        double latitudeRadians = footpointLatitude - (primeVerticalRadius * tanFootpoint / radiusOfCurvature)
                * (Math.pow(d, 2) / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * Math.pow(c1, 2) - 9 * secondEccentricitySquared)
                * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * Math.pow(t1, 2)
                - 252 * secondEccentricitySquared - 3 * Math.pow(c1, 2)) * Math.pow(d, 6) / 720);
        double longitudeRadians = centralMeridian + (d
                - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * Math.pow(c1, 2)
                + 8 * secondEccentricitySquared + 24 * Math.pow(t1, 2)) * Math.pow(d, 5) / 120)
                / cosFootpoint;

        return new Wgs84Coordinate(roundDegrees(Math.toDegrees(latitudeRadians)), roundDegrees(Math.toDegrees(longitudeRadians)));
    }

    private BigDecimal roundDegrees(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    private boolean matches(String expected, String actual) {
        var normalizedExpected = trimToNull(expected);
        return normalizedExpected != null && normalizedExpected.equals(trimToNull(actual));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            var trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String fallback(String label, String id) {
        var trimmedId = trimToNull(id);
        return trimmedId == null ? label : "%s %s".formatted(label, trimmedId);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeAndLimitText(String value, int maximumLength) {
        var trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Wgs84Coordinate(BigDecimal latitude, BigDecimal longitude) {
    }

    private record ReviewedOffStreetOverride(
            String displayName,
            Wgs84Coordinate coordinate,
            String catalogNote
    ) {
    }
}
