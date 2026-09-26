package parking.forecast.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;

public interface SourceStateRepository extends JpaRepository<SourceStateEntity, Long> {

    Optional<SourceStateEntity> findBySourceSystemAndDatasetCode(SourceSystem sourceSystem, DatasetCode datasetCode);
}
