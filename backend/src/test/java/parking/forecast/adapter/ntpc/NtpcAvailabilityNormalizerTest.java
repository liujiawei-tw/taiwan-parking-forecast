package parking.forecast.adapter.ntpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceSystem;

class NtpcAvailabilityNormalizerTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-28T08:00:00Z");

    private final NtpcAvailabilityNormalizer normalizer = new NtpcAvailabilityNormalizer();

    @Test
    void normalizesAvailableRoadsideCarSpotAsRecommendable() {
        var observation = normalizer.normalizeRoadside(roadside("NTPC-001", "CELL-001", "小型車格", "0"), FETCHED_AT);

        assertThat(observation.sourceSystem()).isEqualTo(SourceSystem.NTPC_OPEN_DATA);
        assertThat(observation.datasetCode()).isEqualTo(DatasetCode.NTPC_ROADSIDE_SPOT);
        assertThat(observation.sourcePlaceId()).isEqualTo("NTPC-001");
        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(observation.availableSpot()).isTrue();
        assertThat(observation.rawStatusCode()).isEqualTo("0");
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PASS);
        assertThat(observation.carEligibleStatus()).isEqualTo(EligibilityStatus.CONFIRMED);
        assertThat(observation.currentlyRecommendable()).isTrue();
    }

    @Test
    void doesNotRecommendAvailableRoadsideSpotWhenCarEligibilityIsUnknown() {
        var observation = normalizer.normalizeRoadside(roadside("NTPC-002", "CELL-002", "一般格位", "0"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(observation.availableSpot()).isTrue();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(observation.qualityReason()).isEqualTo("ROADSIDE_AVAILABLE_BUT_CAR_ELIGIBILITY_UNCONFIRMED");
        assertThat(observation.carEligibleStatus()).isEqualTo(EligibilityStatus.UNKNOWN);
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    @Test
    void normalizesOccupiedRoadsideSpot() {
        var observation = normalizer.normalizeRoadside(roadside("NTPC-003", "CELL-003", "小型車格", "1"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.OCCUPIED);
        assertThat(observation.availableSpot()).isFalse();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PASS);
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    @Test
    void preservesUnknownRoadsideStatusWithoutInventingAvailability() {
        var observation = normalizer.normalizeRoadside(roadside("NTPC-004", "CELL-004", "小型車格", "9"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.UNKNOWN);
        assertThat(observation.availableSpot()).isNull();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.UNKNOWN);
        assertThat(observation.qualityReason()).isEqualTo("UNKNOWN_ROADSIDE_CELL_STATUS");
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    @Test
    void failsRoadsideObservationWhenRequiredFieldsAreMissing() {
        var observation = normalizer.normalizeRoadside(roadside(" ", " ", "小型車格", "0"), FETCHED_AT);

        assertThat(observation.sourcePlaceId()).isNull();
        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.UNKNOWN);
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.FAIL);
        assertThat(observation.qualityReason()).isEqualTo("MISSING_ROADSIDE_SOURCE_ID");
    }

    @Test
    void fallsBackToRoadsideCellIdWhenSystemIdIsMissing() {
        var observation = normalizer.normalizeRoadside(roadside(" ", "CELL-FALLBACK", "小型車格", "0"), FETCHED_AT);

        assertThat(observation.sourcePlaceId()).isEqualTo("CELL-FALLBACK");
    }

    @Test
    void normalizesOffStreetPositiveSpacesAsAvailable() {
        var observation = normalizer.normalizeOffStreet(offStreet("P001", "12"), FETCHED_AT);

        assertThat(observation.sourceSystem()).isEqualTo(SourceSystem.NTPC_OPEN_DATA);
        assertThat(observation.datasetCode()).isEqualTo(DatasetCode.NTPC_OFF_STREET_AVAILABILITY);
        assertThat(observation.sourcePlaceId()).isEqualTo("P001");
        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(observation.availableCarSpaces()).isEqualTo(12);
        assertThat(observation.rawAvailabilityValue()).isEqualTo("12");
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PASS);
        assertThat(observation.currentlyRecommendable()).isTrue();
    }

    @Test
    void normalizesOffStreetZeroSpacesAsFull() {
        var observation = normalizer.normalizeOffStreet(offStreet("P002", "0"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.FULL);
        assertThat(observation.availableCarSpaces()).isZero();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PASS);
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    @Test
    void normalizesOffStreetMinusNineAsOutOfServiceWithoutZeroSpaces() {
        var observation = normalizer.normalizeOffStreet(offStreet("P003", "-9"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.OUT_OF_SERVICE);
        assertThat(observation.availableCarSpaces()).isNull();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.PARTIAL_PASS);
        assertThat(observation.qualityReason()).isEqualTo("OFF_STREET_OPERATOR_CHANGE_NO_REALTIME_AVAILABILITY");
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    @Test
    void failsOffStreetObservationWhenAvailableValueIsInvalid() {
        var observation = normalizer.normalizeOffStreet(offStreet("P004", "abc"), FETCHED_AT);

        assertThat(observation.availabilityStatus()).isEqualTo(AvailabilityStatus.UNKNOWN);
        assertThat(observation.availableCarSpaces()).isNull();
        assertThat(observation.qualityStatus()).isEqualTo(QualityStatus.FAIL);
        assertThat(observation.qualityReason()).isEqualTo("INVALID_OFF_STREET_AVAILABLE_CAR");
        assertThat(observation.currentlyRecommendable()).isFalse();
    }

    private NtpcRoadsideParkingAvailabilityDto roadside(String id, String cellId, String name, String cellStatus) {
        return new NtpcRoadsideParkingAvailabilityDto(
                id,
                cellId,
                name,
                "每日",
                "00:00-24:00",
                "收費",
                "每小時 20 元",
                "示範",
                "R001",
                "新莊路",
                cellStatus,
                "1",
                "0",
                "25.0372",
                "121.4525",
                "65000",
                "65000050");
    }

    private NtpcOffStreetParkingAvailabilityDto offStreet(String id, String availableCar) {
        return new NtpcOffStreetParkingAvailabilityDto(id, availableCar);
    }
}
