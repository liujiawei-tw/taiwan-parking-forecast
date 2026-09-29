package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.SourceMeasurementService.SourceMeasurementCommand;

class SourceMeasurementServiceTest {

    private static final SourceSystem SOURCE_SYSTEM = SourceSystem.NTPC_OPEN_DATA;
    private static final DatasetCode DATASET_CODE = DatasetCode.NTPC_ROADSIDE_SPOT;
    private static final Instant STARTED_AT = Instant.parse("2026-09-29T08:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-09-29T08:00:03Z");

    @Test
    void separatesDuplicateMissingUnmappedAndFailedObservationMetrics() {
        SourceMappingRepository repository = mock(SourceMappingRepository.class);
        SourceMappingEntity mappingA = mapping("A-001");
        SourceMappingEntity mappingB = mapping("A-002");
        when(repository.findBySourceSystemAndDatasetCodeAndMappingStatus(SOURCE_SYSTEM, DATASET_CODE, MappingStatus.VERIFIED))
                .thenReturn(List.of(mappingA, mappingB));
        SourceMeasurementService service = new SourceMeasurementService(repository);

        var summary = service.analyze(new SourceMeasurementCommand(
                SOURCE_SYSTEM,
                DATASET_CODE,
                STARTED_AT,
                COMPLETED_AT,
                500,
                20,
                2,
                3,
                12345,
                true,
                List.of(
                        observation("A-001", QualityStatus.PASS),
                        observation("A-001", QualityStatus.PASS),
                        observation("A-404", QualityStatus.PASS),
                        observation(null, QualityStatus.FAIL)
                )
        ));

        assertThat(summary.durationMillis()).isEqualTo(3000);
        assertThat(summary.pageCount()).isEqualTo(2);
        assertThat(summary.httpRequestCount()).isEqualTo(3);
        assertThat(summary.retryCount()).isEqualTo(1);
        assertThat(summary.responseBytes()).isEqualTo(12345);
        assertThat(summary.normalizedCount()).isEqualTo(4);
        assertThat(summary.sourceIdCount()).isEqualTo(3);
        assertThat(summary.uniqueSourceIdCount()).isEqualTo(2);
        assertThat(summary.missingSourceIdCount()).isEqualTo(1);
        assertThat(summary.duplicateSourceIdRows()).isEqualTo(1);
        assertThat(summary.duplicateSourceIdExamples()).containsExactly("A-001");
        assertThat(summary.verifiedMappingCount()).isEqualTo(2);
        assertThat(summary.mappedObservationCount()).isEqualTo(2);
        assertThat(summary.unmappedObservationCount()).isEqualTo(1);
        assertThat(summary.failedObservationCount()).isEqualTo(1);
        assertThat(summary.recommendation()).isEqualTo("PUBLISH_WITH_PARTIAL_QUALITY_REVIEW");
        assertThat(summary.toQualitySummary())
                .contains("responseBytes=12345")
                .contains("retries=1")
                .contains("missingSourceIds=1")
                .contains("duplicateSourceIdRows=1")
                .contains("mapped=2")
                .contains("unmapped=1")
                .contains("failedObservations=1")
                .contains("mappingCoverage=50.0%");
    }

    @Test
    void recommendsRejectingIncompletePageTraversalBeforePublication() {
        SourceMappingRepository repository = mock(SourceMappingRepository.class);
        SourceMappingEntity mapping = mapping("A-001");
        when(repository.findBySourceSystemAndDatasetCodeAndMappingStatus(SOURCE_SYSTEM, DATASET_CODE, MappingStatus.VERIFIED))
                .thenReturn(List.of(mapping));
        SourceMeasurementService service = new SourceMeasurementService(repository);

        var summary = service.analyze(new SourceMeasurementCommand(
                SOURCE_SYSTEM,
                DATASET_CODE,
                STARTED_AT,
                COMPLETED_AT,
                500,
                1,
                1,
                1,
                8000,
                false,
                List.of(observation("A-001", QualityStatus.PASS))
        ));

        assertThat(summary.pageTraversalCompleted()).isFalse();
        assertThat(summary.recommendation()).isEqualTo("REJECT_INCOMPLETE_PAGE_TRAVERSAL");
    }

    private SourceMappingEntity mapping(String sourcePlaceId) {
        SourceMappingEntity mapping = mock(SourceMappingEntity.class);
        when(mapping.getSourcePlaceId()).thenReturn(sourcePlaceId);
        return mapping;
    }

    private NormalizedAvailabilityObservation observation(String sourcePlaceId, QualityStatus qualityStatus) {
        return NormalizedAvailabilityObservation.builder()
                .sourceSystem(SOURCE_SYSTEM)
                .datasetCode(DATASET_CODE)
                .sourcePlaceId(sourcePlaceId)
                .availabilityStatus(qualityStatus == QualityStatus.FAIL ? AvailabilityStatus.UNKNOWN : AvailabilityStatus.AVAILABLE)
                .qualityStatus(qualityStatus)
                .build();
    }
}
