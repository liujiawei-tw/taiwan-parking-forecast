package parking.forecast.adapter.ntpc;

import java.time.Instant;

import org.springframework.stereotype.Component;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceSystem;

@Component
public class NtpcAvailabilityNormalizer {

    private static final String ROADSIDE_AVAILABLE_CODE = "2";
    private static final String ROADSIDE_OCCUPIED_CODE = "1";
    private static final String OFF_STREET_OUT_OF_SERVICE_VALUE = "-9";

    public NormalizedAvailabilityObservation normalizeRoadside(
            NtpcRoadsideParkingAvailabilityDto dto,
            Instant systemFetchedAt) {
        var sourcePlaceId = resolveRoadsideSourcePlaceId(dto);
        var rawStatusCode = trimToNull(dto.parkingStatus());
        var carEligibleStatus = NtpcRoadsideCarEligibility.classify(dto);
        var base = NormalizedAvailabilityObservation.builder()
                .sourceSystem(SourceSystem.NTPC_OPEN_DATA)
                .datasetCode(DatasetCode.NTPC_ROADSIDE_SPOT)
                .sourcePlaceId(sourcePlaceId)
                .rawStatusCode(rawStatusCode)
                .systemFetchedAt(systemFetchedAt)
                .carEligibleStatus(carEligibleStatus);

        if (sourcePlaceId == null) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .availableSpot(null)
                    .qualityStatus(QualityStatus.FAIL)
                    .qualityReason("MISSING_ROADSIDE_SOURCE_ID")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (rawStatusCode == null) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .availableSpot(null)
                    .qualityStatus(QualityStatus.FAIL)
                    .qualityReason("MISSING_ROADSIDE_PARKING_STATUS")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (ROADSIDE_AVAILABLE_CODE.equals(rawStatusCode)) {
            var recommendable = carEligibleStatus == EligibilityStatus.CONFIRMED;
            return base
                    .availabilityStatus(AvailabilityStatus.AVAILABLE)
                    .availableSpot(true)
                    .qualityStatus(recommendable ? QualityStatus.PASS : QualityStatus.PARTIAL_PASS)
                    .qualityReason(recommendable ? "ROADSIDE_SPOT_AVAILABLE" : "ROADSIDE_AVAILABLE_BUT_CAR_ELIGIBILITY_UNCONFIRMED")
                    .currentlyRecommendable(recommendable)
                    .build();
        }
        if (ROADSIDE_OCCUPIED_CODE.equals(rawStatusCode)) {
            return base
                    .availabilityStatus(AvailabilityStatus.OCCUPIED)
                    .availableSpot(false)
                    .qualityStatus(QualityStatus.PASS)
                    .qualityReason("ROADSIDE_SPOT_OCCUPIED")
                    .currentlyRecommendable(false)
                    .build();
        }
        return base
                .availabilityStatus(AvailabilityStatus.UNKNOWN)
                .availableSpot(null)
                .qualityStatus(QualityStatus.UNKNOWN)
                .qualityReason("UNKNOWN_ROADSIDE_PARKING_STATUS")
                .currentlyRecommendable(false)
                .build();
    }

    public NormalizedAvailabilityObservation normalizeOffStreet(
            NtpcOffStreetParkingAvailabilityDto dto,
            Instant systemFetchedAt) {
        var sourcePlaceId = trimToNull(dto.id());
        var rawAvailabilityValue = trimToNull(dto.availableCar());
        var base = NormalizedAvailabilityObservation.builder()
                .sourceSystem(SourceSystem.NTPC_OPEN_DATA)
                .datasetCode(DatasetCode.NTPC_OFF_STREET_AVAILABILITY)
                .sourcePlaceId(sourcePlaceId)
                .rawAvailabilityValue(rawAvailabilityValue)
                .systemFetchedAt(systemFetchedAt)
                .carEligibleStatus(EligibilityStatus.CONFIRMED);

        if (sourcePlaceId == null) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .qualityStatus(QualityStatus.FAIL)
                    .qualityReason("MISSING_OFF_STREET_ID")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (rawAvailabilityValue == null) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .qualityStatus(QualityStatus.FAIL)
                    .qualityReason("MISSING_OFF_STREET_AVAILABLE_CAR")
                    .currentlyRecommendable(false)
                    .build();
        }
        Integer availableCarSpaces;
        try {
            availableCarSpaces = Integer.valueOf(rawAvailabilityValue);
        } catch (NumberFormatException exception) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .qualityStatus(QualityStatus.FAIL)
                    .qualityReason("INVALID_OFF_STREET_AVAILABLE_CAR")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (OFF_STREET_OUT_OF_SERVICE_VALUE.equals(rawAvailabilityValue)) {
            return base
                    .availabilityStatus(AvailabilityStatus.OUT_OF_SERVICE)
                    .availableCarSpaces(null)
                    .qualityStatus(QualityStatus.PARTIAL_PASS)
                    .qualityReason("OFF_STREET_OPERATOR_CHANGE_NO_REALTIME_AVAILABILITY")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (availableCarSpaces < 0) {
            return base
                    .availabilityStatus(AvailabilityStatus.UNKNOWN)
                    .availableCarSpaces(null)
                    .qualityStatus(QualityStatus.UNKNOWN)
                    .qualityReason("UNKNOWN_NEGATIVE_OFF_STREET_AVAILABLE_CAR")
                    .currentlyRecommendable(false)
                    .build();
        }
        if (availableCarSpaces == 0) {
            return base
                    .availabilityStatus(AvailabilityStatus.FULL)
                    .availableCarSpaces(0)
                    .qualityStatus(QualityStatus.PASS)
                    .qualityReason("OFF_STREET_FULL")
                    .currentlyRecommendable(false)
                    .build();
        }
        return base
                .availabilityStatus(AvailabilityStatus.AVAILABLE)
                .availableCarSpaces(availableCarSpaces)
                .qualityStatus(QualityStatus.PASS)
                .qualityReason("OFF_STREET_AVAILABLE")
                .currentlyRecommendable(true)
                .build();
    }

    private String resolveRoadsideSourcePlaceId(NtpcRoadsideParkingAvailabilityDto dto) {
        var systemId = trimToNull(dto.id());
        if (systemId != null) {
            return systemId;
        }
        return trimToNull(dto.cellId());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
