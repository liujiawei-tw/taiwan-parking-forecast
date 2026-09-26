package parking.forecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "ingestion_run",
        indexes = {
                @Index(name = "idx_ingestion_run_source_started", columnList = "source_system, dataset_code, started_at"),
                @Index(name = "idx_ingestion_run_status", columnList = "run_status")
        }
)
public class IngestionRunEntity {

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
    @Column(name = "trigger_type", nullable = false, length = 60)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 40)
    private IngestionRunStatus runStatus;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "http_request_count")
    private Integer httpRequestCount;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Lob
    @Column(name = "quality_summary")
    private String qualitySummary;

    protected IngestionRunEntity() {
    }

    public IngestionRunEntity(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            TriggerType triggerType,
            IngestionRunStatus runStatus,
            Instant startedAt
    ) {
        this.sourceSystem = sourceSystem;
        this.datasetCode = datasetCode;
        this.triggerType = triggerType;
        this.runStatus = runStatus;
        this.startedAt = startedAt;
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

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public IngestionRunStatus getRunStatus() {
        return runStatus;
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

    public Integer getHttpRequestCount() {
        return httpRequestCount;
    }

    public void setHttpRequestCount(Integer httpRequestCount) {
        this.httpRequestCount = httpRequestCount;
    }

    public Integer getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(Integer recordCount) {
        this.recordCount = recordCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getQualitySummary() {
        return qualitySummary;
    }

    public void setQualitySummary(String qualitySummary) {
        this.qualitySummary = qualitySummary;
    }
}
