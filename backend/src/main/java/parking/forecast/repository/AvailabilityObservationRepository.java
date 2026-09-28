package parking.forecast.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.ParkingPlaceEntity;

public interface AvailabilityObservationRepository extends JpaRepository<AvailabilityObservationEntity, Long> {

    List<AvailabilityObservationEntity> findBySnapshot(AvailabilitySnapshotEntity snapshot);

    List<AvailabilityObservationEntity> findBySnapshotAndParkingPlace_IdIn(
            AvailabilitySnapshotEntity snapshot,
            Collection<Long> parkingPlaceIds
    );

    List<AvailabilityObservationEntity> findByParkingPlaceOrderBySystemFetchedAtDesc(ParkingPlaceEntity parkingPlace);
}
