package parking.forecast.domain;

public enum AvailabilityStatus {
    AVAILABLE,
    OCCUPIED,
    FULL,
    UNKNOWN,
    NO_REALTIME_AVAILABILITY,
    OUT_OF_SERVICE,
    STALE
}
