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
        name = "source_mapping",
        indexes = {
                @Index(name = "idx_source_mapping_source_id", columnList = "source_system, dataset_code, source_place_id"),
                @Index(name = "idx_source_mapping_place", columnList = "parking_place_id")
        }
)
public class SourceMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 60)
    private SourceSystem sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_code", nullable = false, length = 80)
    private DatasetCode datasetCode;

    @Column(name = "source_place_id", nullable = false, length = 120)
    private String sourcePlaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parking_place_id", nullable = false)
    private ParkingPlaceEntity parkingPlace;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_status", nullable = false, length = 40)
    private MappingStatus mappingStatus;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "raw_name_hint", length = 200)
    private String rawNameHint;

    @Column(name = "raw_location_hint", length = 300)
    private String rawLocationHint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceMappingEntity() {
    }

    public SourceMappingEntity(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            String sourcePlaceId,
            ParkingPlaceEntity parkingPlace,
            MappingStatus mappingStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.sourceSystem = sourceSystem;
        this.datasetCode = datasetCode;
        this.sourcePlaceId = sourcePlaceId;
        this.parkingPlace = parkingPlace;
        this.mappingStatus = mappingStatus;
        this.createdAt = createdAt;
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

    public String getSourcePlaceId() {
        return sourcePlaceId;
    }

    public ParkingPlaceEntity getParkingPlace() {
        return parkingPlace;
    }

    public MappingStatus getMappingStatus() {
        return mappingStatus;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public String getRawNameHint() {
        return rawNameHint;
    }

    public void setRawNameHint(String rawNameHint) {
        this.rawNameHint = rawNameHint;
    }

    public String getRawLocationHint() {
        return rawLocationHint;
    }

    public void setRawLocationHint(String rawLocationHint) {
        this.rawLocationHint = rawLocationHint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
