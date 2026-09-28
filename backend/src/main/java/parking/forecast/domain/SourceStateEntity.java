package parking.forecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "source_state",
        indexes = {
                @Index(name = "idx_source_state_source", columnList = "source_system, dataset_code"),
                @Index(name = "idx_source_state_next_refresh", columnList = "next_allowed_refresh_at")
        }
)
public class SourceStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 60)
    private SourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_code", nullable = false, length = 80)
    private DatasetCode datasetCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_snapshot_id")
    private AvailabilitySnapshotEntity activeSnapshot;

    @Column(name = "last_successful_fetched_at")
    private Instant lastSuccessfulFetchedAt;

    @Column(name = "last_source_observed_at")
    private Instant lastSourceObservedAt;

    @Column(name = "next_allowed_refresh_at")
    private Instant nextAllowedRefreshAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "refresh_status", nullable = false, length = 40)
    private RefreshStatus refreshStatus;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceStateEntity() {
    }

    public SourceStateEntity(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            RefreshStatus refreshStatus,
            Instant updatedAt
    ) {
        this.sourceSystem = sourceSystem;
        this.datasetCode = datasetCode;
        this.refreshStatus = refreshStatus;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public DatasetCode getDatasetCode() {
        return datasetCode;
    }

    public AvailabilitySnapshotEntity getActiveSnapshot() {
        return activeSnapshot;
    }

    public void setActiveSnapshot(AvailabilitySnapshotEntity activeSnapshot) {
        this.activeSnapshot = activeSnapshot;
    }

    public Instant getLastSuccessfulFetchedAt() {
        return lastSuccessfulFetchedAt;
    }

    public void setLastSuccessfulFetchedAt(Instant lastSuccessfulFetchedAt) {
        this.lastSuccessfulFetchedAt = lastSuccessfulFetchedAt;
    }

    public Instant getLastSourceObservedAt() {
        return lastSourceObservedAt;
    }

    public void setLastSourceObservedAt(Instant lastSourceObservedAt) {
        this.lastSourceObservedAt = lastSourceObservedAt;
    }

    public Instant getNextAllowedRefreshAt() {
        return nextAllowedRefreshAt;
    }

    public void setNextAllowedRefreshAt(Instant nextAllowedRefreshAt) {
        this.nextAllowedRefreshAt = nextAllowedRefreshAt;
    }

    public RefreshStatus getRefreshStatus() {
        return refreshStatus;
    }

    public void setRefreshStatus(RefreshStatus refreshStatus) {
        this.refreshStatus = refreshStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getConsecutiveFailureCount() {
        return consecutiveFailureCount;
    }

    public void setConsecutiveFailureCount(int consecutiveFailureCount) {
        this.consecutiveFailureCount = consecutiveFailureCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
