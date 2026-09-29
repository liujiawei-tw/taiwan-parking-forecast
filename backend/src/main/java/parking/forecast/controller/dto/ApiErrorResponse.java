package parking.forecast.controller.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiErrorResponse(
        ErrorBody error,
        OffsetDateTime serverTime
) {

    public record ErrorBody(
            String code,
            String message,
            Map<String, Object> details,
            boolean retryable,
            OffsetDateTime nextAllowedAt
    ) {
    }
}
