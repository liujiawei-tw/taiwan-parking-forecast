package parking.forecast.controller;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import parking.forecast.controller.dto.ApiErrorResponse;
import parking.forecast.controller.dto.ApiErrorResponse.ErrorBody;
import parking.forecast.service.ParkingQueryService.ParkingQueryContractException;
import parking.forecast.service.ParkingQueryService.ParkingQueryExpiredException;
import parking.forecast.service.ParkingQueryService.ParkingQueryNotFoundException;
import parking.forecast.service.RedisParkingQueryStateStore.ParkingQueryStateStoreException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ParkingQueryContractException.class)
    public ResponseEntity<ApiErrorResponse> handleContract(ParkingQueryContractException exception) {
        HttpStatus status = switch (exception.code()) {
            case "UNSUPPORTED_AREA", "INVALID_DEPARTURE_TIME" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> details = "UNSUPPORTED_AREA".equals(exception.code())
                ? Map.of("serviceArea", "NEW_TAIPEI_XINZHUANG")
                : Map.of();
        return error(status, exception.code(), exception.getMessage(), details, exception.retryable(), null);
    }

    @ExceptionHandler(ParkingQueryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ParkingQueryNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "QUERY_NOT_FOUND", "Query not found.", Map.of(), false, null);
    }

    @ExceptionHandler(ParkingQueryExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleExpired(ParkingQueryExpiredException exception) {
        return error(HttpStatus.GONE, "QUERY_EXPIRED", "Query expired. Please create a new parking query.", Map.of(), false, null);
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is missing or invalid.", Map.of(), false, null);
    }

    @ExceptionHandler(ParkingQueryStateStoreException.class)
    public ResponseEntity<ApiErrorResponse> handleQueryStateUnavailable(ParkingQueryStateStoreException exception) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_TEMPORARILY_UNAVAILABLE",
                "Query state store is temporarily unavailable.",
                Map.of("component", "redis_query_state"),
                true,
                null
        );
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details,
            boolean retryable,
            OffsetDateTime nextAllowedAt
    ) {
        OffsetDateTime serverTime = OffsetDateTime.now(ZoneOffset.UTC);
        ApiErrorResponse response = new ApiErrorResponse(
                new ErrorBody(code, message, details, retryable, nextAllowedAt),
                serverTime
        );
        return ResponseEntity.status(status).body(response);
    }
}
