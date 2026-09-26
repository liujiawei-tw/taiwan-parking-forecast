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
        name = "availability_observation",
        indexes = {
                @Index(name = "idx_availability_observation_snapshot_place", columnList = "snapshot_id, parking_place_id"),
                @Index(name = "idx_availability_observation_place_fetched", columnList = "parking_place_id, system_fetched_at"),
                @Index(name = "idx_availability_observation_dataset_fetched", columnList = "dataset_code, system_fetched_at")
        }
)
public class AvailabilityObservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private AvailabilitySnapshotEntity snapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parking_place_id", nullable = false)
    private ParkingPlaceEntity parkingPlace;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 60)
    private SourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_code", nullable = false, length = 80)
    private DatasetCode datasetCode;

    @Column(name = "source_place_id", nullable = false, length = 120)
    private String sourcePlaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 50)
    private AvailabilityStatus availabilityStatus;

    @Column(name = "available_car_spaces")
    private Integer availableCarSpaces;

    @Column(name = "total_car_spaces")
    private Integer totalCarSpaces;

    @Column(name = "is_available_spot")
    private Boolean availableSpot;

    @Column(name = "raw_status_code", length = 80)
    private String rawStatusCode;

    @Column(name = "raw_availability_value", length = 80)
    private String rawAvailabilityValue;

    @Column(name = "source_observed_at")
    private Instant sourceObservedAt;

    @Column(name = "system_fetched_at", nullable = false)
    private Instant systemFetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 40)
    private QualityStatus qualityStatus;

    @Column(name = "quality_reason", length = 500)
    private String qualityReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AvailabilityObservationEntity() {
    }

    public AvailabilityObservationEntity(
            AvailabilitySnapshotEntity snapshot,
            ParkingPlaceEntity parkingPlace,
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            String sourcePlaceId,
            AvailabilityStatus availabilityStatus,
            Instant systemFetchedAt,
            QualityStatus qualityStatus,
            Instant createdAt
    ) {
        this.snapshot = snapshot;
        this.parkingPlace = parkingPlace;
        this.sourceSystem = sourceSystem;
        this.datasetCode = datasetCode;
        this.sourcePlaceId = sourcePlaceId;
        this.availabilityStatus = availabilityStatus;
        this.systemFetchedAt = systemFetchedAt;
        this.qualityStatus = qualityStatus;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public AvailabilitySnapshotEntity getSnapshot() {
        return snapshot;
    }

    public ParkingPlaceEntity getParkingPlace() {
        return parkingPlace;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public DatasetCode getDatasetCode() {
        return datasetCode;
    }

    public String getSourcePlaceId() {
        return sourcePlaceId;
    }

    public AvailabilityStatus getAvailabilityStatus() {
        return availabilityStatus;
    }

    public Integer getAvailableCarSpaces() {
        return availableCarSpaces;
    }

    public void setAvailableCarSpaces(Integer availableCarSpaces) {
        this.availableCarSpaces = availableCarSpaces;
    }

    public Integer getTotalCarSpaces() {
        return totalCarSpaces;
    }

    public void setTotalCarSpaces(Integer totalCarSpaces) {
        this.totalCarSpaces = totalCarSpaces;
    }

    public Boolean getAvailableSpot() {
        return availableSpot;
    }

    public void setAvailableSpot(Boolean availableSpot) {
        this.availableSpot = availableSpot;
    }

    public String getRawStatusCode() {
        return rawStatusCode;
    }

    public void setRawStatusCode(String rawStatusCode) {
        this.rawStatusCode = rawStatusCode;
    }

    public String getRawAvailabilityValue() {
        return rawAvailabilityValue;
    }

    public void setRawAvailabilityValue(String rawAvailabilityValue) {
        this.rawAvailabilityValue = rawAvailabilityValue;
    }

    public Instant getSourceObservedAt() {
        return sourceObservedAt;
    }

    public void setSourceObservedAt(Instant sourceObservedAt) {
        this.sourceObservedAt = sourceObservedAt;
    }

    public Instant getSystemFetchedAt() {
        return systemFetchedAt;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
