package parking.forecast.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceSystem;

public interface AvailabilitySnapshotRepository extends JpaRepository<AvailabilitySnapshotEntity, Long> {

    List<AvailabilitySnapshotEntity> findBySourceSystemAndDatasetCodeAndSnapshotStatus(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            SnapshotStatus snapshotStatus
    );
}
