package parking.forecast.adapter.ntpc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
        var coordinate = twd97ToWgs84(row.tw97x(), row.tw97y());
        return new CatalogSourceRecord(
                serviceArea,
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                trimToNull(row.id()),
                ParkingPlaceType.OFF_STREET_LOT,
                normalizeAndLimitText(firstNonBlank(row.name(), fallback("路外停車場", row.id())), 200),
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
                normalizeAndLimitText(row.summary(), 2000)
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
            return inverseTaiwanTm2(x, y);
        } catch (NumberFormatException exception) {
            return null;
        }
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
}
