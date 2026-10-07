package parking.forecast.adapter.ntpc;

import java.util.List;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.domain.EligibilityStatus;

final class NtpcRoadsideCarEligibility {
    private NtpcRoadsideCarEligibility() { }

    static EligibilityStatus classify(NtpcRoadsideParkingAvailabilityDto row) {
        String type = row.name() == null ? "" : row.name().trim();
        if (type.contains("機車") || type.contains("大型車") || type.contains("身心障礙")) {
            return EligibilityStatus.NOT_ELIGIBLE;
        }
        String restrictions = type + (row.memo() == null ? "" : row.memo());
        if (restrictions.contains("專用") || restrictions.contains("禁停") || restrictions.contains("接送")) {
            return EligibilityStatus.UNKNOWN;
        }
        return List.of("汽車停車位", "小型車停車位", "汽車", "小型車", "小型車格").contains(type)
                ? EligibilityStatus.CONFIRMED : EligibilityStatus.UNKNOWN;
    }
}
