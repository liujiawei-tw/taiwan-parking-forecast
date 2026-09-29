package parking.forecast.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.SourceMappingRepository;

@Service
public class SourceMeasurementService {

    private final SourceMappingRepository sourceMappingRepository;

    public SourceMeasurementService(SourceMappingRepository sourceMappingRepository) {
        this.sourceMappingRepository = sourceMappingRepository;
    }

    public SourceMeasurementSummary analyze(SourceMeasurementCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        command.validate();

        List<String> sourceIds = command.observations().stream()
                .map(NormalizedAvailabilityObservation::sourcePlaceId)
                .filter(StringUtils::hasText)
                .toList();
        Set<String> uniqueSourceIds = new HashSet<>(sourceIds);
        Set<String> verifiedSourceIds = sourceMappingRepository.findBySourceSystemAndDatasetCodeAndMappingStatus(
                        command.sourceSystem(),
                        command.datasetCode(),
                        MappingStatus.VERIFIED
                ).stream()
                .map(SourceMappingEntity::getSourcePlaceId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        long mappedObservationCount = sourceIds.stream().filter(verifiedSourceIds::contains).count();
        long failedObservationCount = command.observations().stream()
                .filter(observation -> observation.qualityStatus() == QualityStatus.FAIL)
                .count();
        Map<String, Long> sourceIdCounts = sourceIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        List<String> duplicateExamples = sourceIdCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .limit(5)
                .toList();

        int missingSourceIdCount = command.observations().size() - sourceIds.size();
        int duplicateSourceIdRows = sourceIds.size() - uniqueSourceIds.size();
        int unmappedObservationCount = sourceIds.size() - Math.toIntExact(mappedObservationCount);
        String recommendation = recommendation(
                command.pageTraversalCompleted(),
                command.observations().size(),
                mappedObservationCount,
                missingSourceIdCount,
                duplicateSourceIdRows,
                unmappedObservationCount,
                failedObservationCount
        );

        return new SourceMeasurementSummary(
                command.sourceSystem(),
                command.datasetCode(),
                command.startedAt(),
                command.completedAt(),
                Math.max(0, Duration.between(command.startedAt(), command.completedAt()).toMillis()),
                command.pageSize(),
                command.maxPages(),
                command.pageCount(),
                command.httpRequestCount(),
                Math.max(0, command.httpRequestCount() - command.pageCount()),
                command.responseBytes(),
                command.pageTraversalCompleted(),
                command.observations().size(),
                sourceIds.size(),
                uniqueSourceIds.size(),
                missingSourceIdCount,
                duplicateSourceIdRows,
                duplicateExamples,
                verifiedSourceIds.size(),
                Math.toIntExact(mappedObservationCount),
                unmappedObservationCount,
                Math.toIntExact(failedObservationCount),
                recommendation
        );
    }

    private String recommendation(
            boolean pageTraversalCompleted,
            int normalizedCount,
            long mappedObservationCount,
            int missingSourceIdCount,
            int duplicateSourceIdRows,
            int unmappedObservationCount,
            long failedObservationCount
    ) {
        if (!pageTraversalCompleted) {
            return "REJECT_INCOMPLETE_PAGE_TRAVERSAL";
        }
        if (normalizedCount == 0) {
            return "REJECT_NO_NORMALIZED_OBSERVATIONS";
        }
        if (mappedObservationCount == 0) {
            return "REJECT_NO_MAPPED_OBSERVATIONS";
        }
        if (missingSourceIdCount > 0 || duplicateSourceIdRows > 0 || unmappedObservationCount > 0 || failedObservationCount > 0) {
            return "PUBLISH_WITH_PARTIAL_QUALITY_REVIEW";
        }
        return "PUBLISH_MEETS_CURRENT_MVP_THRESHOLD";
    }

    public record SourceMeasurementCommand(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            Instant startedAt,
            Instant completedAt,
            int pageSize,
            int maxPages,
            int pageCount,
            int httpRequestCount,
            long responseBytes,
            boolean pageTraversalCompleted,
            List<NormalizedAvailabilityObservation> observations
    ) {
        public SourceMeasurementCommand {
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        private void validate() {
            if (sourceSystem == null) {
                throw new IllegalArgumentException("sourceSystem must not be null");
            }
            if (datasetCode == null) {
                throw new IllegalArgumentException("datasetCode must not be null");
            }
            if (startedAt == null) {
                throw new IllegalArgumentException("startedAt must not be null");
            }
            if (completedAt == null) {
                throw new IllegalArgumentException("completedAt must not be null");
            }
            if (pageSize <= 0) {
                throw new IllegalArgumentException("pageSize must be greater than 0");
            }
            if (maxPages <= 0) {
                throw new IllegalArgumentException("maxPages must be greater than 0");
            }
            if (pageCount < 0) {
                throw new IllegalArgumentException("pageCount must not be negative");
            }
            if (httpRequestCount < 0) {
                throw new IllegalArgumentException("httpRequestCount must not be negative");
            }
            if (responseBytes < 0) {
                throw new IllegalArgumentException("responseBytes must not be negative");
            }
        }
    }

    public record SourceMeasurementSummary(
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            Instant startedAt,
            Instant completedAt,
            long durationMillis,
            int pageSize,
            int maxPages,
            int pageCount,
            int httpRequestCount,
            int retryCount,
            long responseBytes,
            boolean pageTraversalCompleted,
            int normalizedCount,
            int sourceIdCount,
            int uniqueSourceIdCount,
            int missingSourceIdCount,
            int duplicateSourceIdRows,
            List<String> duplicateSourceIdExamples,
            int verifiedMappingCount,
            int mappedObservationCount,
            int unmappedObservationCount,
            int failedObservationCount,
            String recommendation
    ) {
        public SourceMeasurementSummary {
            duplicateSourceIdExamples = duplicateSourceIdExamples == null ? List.of() : List.copyOf(duplicateSourceIdExamples);
        }

        public String toQualitySummary() {
            return String.format(
                    Locale.ROOT,
                    "sourceMeasurement{source=%s, dataset=%s, startedAt=%s, completedAt=%s, durationMs=%d, pageSize=%d, maxPages=%d, pages=%d, httpRequests=%d, retries=%d, responseBytes=%d, completedPages=%s, normalized=%d, sourceIds=%d, uniqueSourceIds=%d, missingSourceIds=%d, duplicateSourceIdRows=%d, duplicateExamples=%s, verifiedMappings=%d, mapped=%d, unmapped=%d, failedObservations=%d, mappingCoverage=%.1f%%, recommendation=%s}",
                    sourceSystem,
                    datasetCode,
                    startedAt,
                    completedAt,
                    durationMillis,
                    pageSize,
                    maxPages,
                    pageCount,
                    httpRequestCount,
                    retryCount,
                    responseBytes,
                    pageTraversalCompleted,
                    normalizedCount,
                    sourceIdCount,
                    uniqueSourceIdCount,
                    missingSourceIdCount,
                    duplicateSourceIdRows,
                    duplicateSourceIdExamples,
                    verifiedMappingCount,
                    mappedObservationCount,
                    unmappedObservationCount,
                    failedObservationCount,
                    mappingCoveragePercent(),
                    recommendation
            );
        }

        private double mappingCoveragePercent() {
            if (normalizedCount == 0) {
                return 0.0;
            }
            return mappedObservationCount * 100.0 / normalizedCount;
        }
    }
}
