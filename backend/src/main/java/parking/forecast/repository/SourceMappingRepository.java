package parking.forecast.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;

public interface SourceMappingRepository extends JpaRepository<SourceMappingEntity, Long> {

    Optional<SourceMappingEntity> findBySourceSystemAndDatasetCodeAndSourcePlaceId(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            String sourcePlaceId
    );

    List<SourceMappingEntity> findBySourceSystemAndDatasetCodeAndMappingStatus(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            MappingStatus mappingStatus
    );
}
