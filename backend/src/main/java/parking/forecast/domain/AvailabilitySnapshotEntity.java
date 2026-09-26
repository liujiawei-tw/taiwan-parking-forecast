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
        name = "availability_snapshot",
        indexes = {
                @Index(name = "idx_availability_snapshot_source_status", columnList = "source_system, dataset_code, snapshot_status"),
                @Index(name = "idx_availability_snapshot_published", columnList = "published_at")
        }
)
public class AvailabilitySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 60)
    private SourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_code", nullable = false, length = 80)
    private DatasetCode datasetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_status", nullable = false, length = 40)
    private SnapshotStatus snapshotStatus;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "system_fetched_at")
    private Instant systemFetchedAt;

    @Column(name = "source_observed_at")
    private Instant sourceObservedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "expected_record_count")
    private Integer expectedRecordCount;

    @Column(name = "actual_record_count")
    private Integer actualRecordCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 40)
    private QualityStatus qualityStatus;

    @Column(name = "quality_reason", length = 500)
    private String qualityReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingestion_run_id")
    private IngestionRunEntity ingestionRun;

    protected AvailabilitySnapshotEntity() {
    }

    public AvailabilitySnapshotEntity(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            SnapshotStatus snapshotStatus,
            Instant startedAt,
            QualityStatus qualityStatus
    ) {
        this.sourceSystem = sourceSystem;
        this.datasetCode = datasetCode;
        this.snapshotStatus = snapshotStatus;
        this.startedAt = startedAt;
        this.qualityStatus = qualityStatus;
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

    public SnapshotStatus getSnapshotStatus() {
        return snapshotStatus;
    }

    public void setSnapshotStatus(SnapshotStatus snapshotStatus) {
        this.snapshotStatus = snapshotStatus;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getSystemFetchedAt() {
        return systemFetchedAt;
    }

    public void setSystemFetchedAt(Instant systemFetchedAt) {
        this.systemFetchedAt = systemFetchedAt;
    }

    public Instant getSourceObservedAt() {
        return sourceObservedAt;
    }

    public void setSourceObservedAt(Instant sourceObservedAt) {
        this.sourceObservedAt = sourceObservedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Integer getExpectedRecordCount() {
        return expectedRecordCount;
    }

    public void setExpectedRecordCount(Integer expectedRecordCount) {
        this.expectedRecordCount = expectedRecordCount;
    }

    public Integer getActualRecordCount() {
        return actualRecordCount;
    }

    public void setActualRecordCount(Integer actualRecordCount) {
        this.actualRecordCount = actualRecordCount;
    }

    public QualityStatus getQualityStatus() {
        return qualityStatus;
    }

    public void setQualityStatus(QualityStatus qualityStatus) {
        this.qualityStatus = qualityStatus;
    }

    public String getQualityReason() {
        return qualityReason;
    }

    public void setQualityReason(String qualityReason) {
        this.qualityReason = qualityReason;
    }

    public IngestionRunEntity getIngestionRun() {
        return ingestionRun;
    }

    public void setIngestionRun(IngestionRunEntity ingestionRun) {
        this.ingestionRun = ingestionRun;
    }
}
