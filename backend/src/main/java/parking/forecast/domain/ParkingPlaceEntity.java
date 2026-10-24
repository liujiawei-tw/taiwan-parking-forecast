package parking.forecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "parking_place",
        indexes = {
                @Index(name = "idx_parking_place_area_type_status", columnList = "service_area, place_type, active_status"),
                @Index(name = "idx_parking_place_lat_lng", columnList = "latitude, longitude")
        }
)
public class ParkingPlaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 40)
    private ParkingPlaceType placeType;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "parking_space_type", length = 120)
    private String parkingSpaceType;

    @Column(name = "fee_status", length = 120)
    private String feeStatus;

    @Column(name = "fee_description", length = 1000)
    private String feeDescription;

    @Column(name = "charging_days", length = 250)
    private String chargingDays;

    @Column(name = "charging_hours", length = 250)
    private String chargingHours;

    @Column(name = "total_car_spaces")
    private Integer totalCarSpaces;

    @Column(name = "catalog_notes", length = 2000)
    private String catalogNotes;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "entrance_latitude", precision = 10, scale = 7)
    private BigDecimal entranceLatitude;

    @Column(name = "entrance_longitude", precision = 10, scale = 7)
    private BigDecimal entranceLongitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "car_eligible_status", nullable = false, length = 40)
    private EligibilityStatus carEligibleStatus = EligibilityStatus.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_status", nullable = false, length = 40)
    private ActiveStatus activeStatus = ActiveStatus.UNKNOWN;

    @Column(name = "service_area", nullable = false, length = 80)
    private String serviceArea;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingPlaceEntity() {
    }

    public ParkingPlaceEntity(
            ParkingPlaceType placeType,
            String displayName,
            BigDecimal latitude,
            BigDecimal longitude,
            EligibilityStatus carEligibleStatus,
            ActiveStatus activeStatus,
            String serviceArea,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.placeType = placeType;
        this.displayName = displayName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.carEligibleStatus = carEligibleStatus;
        this.activeStatus = activeStatus;
        this.serviceArea = serviceArea;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public ParkingPlaceType getPlaceType() {
        return placeType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getParkingSpaceType() {
        return parkingSpaceType;
    }

    public void setParkingSpaceType(String parkingSpaceType) {
        this.parkingSpaceType = parkingSpaceType;
    }

    public String getFeeStatus() {
        return feeStatus;
    }

    public void setFeeStatus(String feeStatus) {
        this.feeStatus = feeStatus;
    }

    public String getFeeDescription() {
        return feeDescription;
    }

    public void setFeeDescription(String feeDescription) {
        this.feeDescription = feeDescription;
    }

    public String getChargingDays() {
        return chargingDays;
    }

    public void setChargingDays(String chargingDays) {
        this.chargingDays = chargingDays;
    }

    public String getChargingHours() {
        return chargingHours;
    }

    public void setChargingHours(String chargingHours) {
        this.chargingHours = chargingHours;
    }

    public Integer getTotalCarSpaces() {
        return totalCarSpaces;
    }

    public void setTotalCarSpaces(Integer totalCarSpaces) {
        this.totalCarSpaces = totalCarSpaces;
    }

    public String getCatalogNotes() {
        return catalogNotes;
    }

    public void setCatalogNotes(String catalogNotes) {
        this.catalogNotes = catalogNotes;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public BigDecimal getEntranceLatitude() {
        return entranceLatitude;
    }

    public void setEntranceLatitude(BigDecimal entranceLatitude) {
        this.entranceLatitude = entranceLatitude;
    }

    public BigDecimal getEntranceLongitude() {
        return entranceLongitude;
    }

    public void setEntranceLongitude(BigDecimal entranceLongitude) {
        this.entranceLongitude = entranceLongitude;
    }

    public EligibilityStatus getCarEligibleStatus() {
        return carEligibleStatus;
    }

    public ActiveStatus getActiveStatus() {
        return activeStatus;
    }

    public String getServiceArea() {
        return serviceArea;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
