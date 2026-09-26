package parking.forecast.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.IngestionRunEntity;
import parking.forecast.domain.IngestionRunStatus;
import parking.forecast.domain.SourceSystem;

public interface IngestionRunRepository extends JpaRepository<IngestionRunEntity, Long> {

    List<IngestionRunEntity> findBySourceSystemAndDatasetCodeAndRunStatus(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            IngestionRunStatus runStatus
    );
}
