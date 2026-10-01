package parking.forecast.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.SnapshotStatus;

public interface AvailabilityObservationRepository extends JpaRepository<AvailabilityObservationEntity, Long> {

    List<AvailabilityObservationEntity> findBySnapshot(AvailabilitySnapshotEntity snapshot);

    List<AvailabilityObservationEntity> findBySnapshotAndParkingPlace_IdIn(
            AvailabilitySnapshotEntity snapshot,
            Collection<Long> parkingPlaceIds
    );

    List<AvailabilityObservationEntity> findByParkingPlaceOrderBySystemFetchedAtDesc(ParkingPlaceEntity parkingPlace);

    List<AvailabilityObservationEntity> findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
            SnapshotStatus snapshotStatus,
            DatasetCode datasetCode,
            Instant fetchedFromInclusive,
            Instant fetchedToExclusive
    );
}
