package parking.forecast.domain;

public enum RefreshStatus {
    IDLE,
    REFRESHING,
    COOLDOWN,
    FAILED,
    BUDGET_BLOCKED
}
