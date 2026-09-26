package parking.forecast.domain;

public enum TriggerType {
    INITIAL_QUERY,
    VISIBLE_AUTO_REFRESH,
    MANUAL_REFRESH,
    BACKGROUND_SAMPLING,
    SYSTEM_SCHEDULED
}
