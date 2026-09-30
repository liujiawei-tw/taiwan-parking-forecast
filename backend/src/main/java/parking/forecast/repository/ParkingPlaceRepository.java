package parking.forecast.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;

public interface ParkingPlaceRepository extends JpaRepository<ParkingPlaceEntity, Long> {

    List<ParkingPlaceEntity> findByServiceAreaAndActiveStatus(String serviceArea, ActiveStatus activeStatus);

    List<ParkingPlaceEntity> findByServiceAreaAndPlaceTypeAndActiveStatus(
            String serviceArea,
            ParkingPlaceType placeType,
            ActiveStatus activeStatus
    );

    long countByServiceAreaAndPlaceTypeAndActiveStatus(
            String serviceArea,
            ParkingPlaceType placeType,
            ActiveStatus activeStatus
    );

    List<ParkingPlaceEntity> findByServiceAreaAndActiveStatusAndLatitudeBetweenAndLongitudeBetween(
            String serviceArea,
            ActiveStatus activeStatus,
            BigDecimal minLatitude,
            BigDecimal maxLatitude,
            BigDecimal minLongitude,
            BigDecimal maxLongitude
    );
}
