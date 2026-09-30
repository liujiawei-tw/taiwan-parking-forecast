package parking.forecast.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.SourceMappingRepository;

@Service
public class CatalogImportDryRunService {

    private final SourceMappingRepository sourceMappingRepository;

    public CatalogImportDryRunService(SourceMappingRepository sourceMappingRepository) {
        this.sourceMappingRepository = sourceMappingRepository;
    }

    public CatalogImportDryRunReport dryRun(CatalogImportScope scope, List<CatalogSourceRecord> records) {
        List<CatalogSourceRecord> scopedRecords = records.stream()
                .filter(record -> scope.serviceArea().equals(record.serviceArea()))
                .toList();

        Set<SourceKey> seen = new HashSet<>();
        Set<SourceKey> duplicates = new HashSet<>();
        int missingSourceIds = 0;
        int invalidCoordinates = 0;
        int unknownEligibility = 0;
        int acceptedCandidates = 0;

        for (CatalogSourceRecord record : scopedRecords) {
            SourceKey sourceKey = record.sourceKey();
            if (!sourceKey.hasSourcePlaceId()) {
                missingSourceIds++;
            } else if (!seen.add(sourceKey)) {
                duplicates.add(sourceKey);
            }

            if (!record.hasValidCoordinates()) {
                invalidCoordinates++;
            }
            if (record.carEligibleStatus() == EligibilityStatus.UNKNOWN) {
                unknownEligibility++;
            }
            if (record.isAcceptableCatalogCandidate()) {
                acceptedCandidates++;
            }
        }

        long verifiedMappings = scope.datasets().stream()
                .mapToLong(datasetCode -> sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                        scope.sourceSystem(),
                        datasetCode,
                        MappingStatus.VERIFIED
                ))
                .sum();

        return new CatalogImportDryRunReport(
                scope.serviceArea(),
                scope.sourceSystem(),
                scope.datasets(),
                scopedRecords.size(),
                acceptedCandidates,
                missingSourceIds,
                duplicates.size(),
                invalidCoordinates,
                unknownEligibility,
                verifiedMappings,
                percent(acceptedCandidates, scopedRecords.size()),
                cappedPercent(verifiedMappings, scopedRecords.size()),
                resolveRecommendation(scopedRecords.size(), acceptedCandidates, missingSourceIds, duplicates.size(), invalidCoordinates, unknownEligibility)
        );
    }

    private ImportRecommendation resolveRecommendation(
            int scopedRows,
            int acceptedCandidates,
            int missingSourceIds,
            int duplicateSourceIds,
            int invalidCoordinates,
            int unknownEligibility
    ) {
        if (scopedRows == 0 || acceptedCandidates == 0) {
            return ImportRecommendation.REJECT_NO_ACCEPTED_CANDIDATES;
        }
        if (missingSourceIds > 0 || duplicateSourceIds > 0 || invalidCoordinates > 0) {
            return ImportRecommendation.REVIEW_SOURCE_ID_OR_COORDINATE_QUALITY;
        }
        if (unknownEligibility > 0) {
            return ImportRecommendation.REVIEW_ELIGIBILITY_RULES;
        }
        return ImportRecommendation.READY_FOR_IMPORT_REVIEW;
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal cappedPercent(long numerator, long denominator) {
        return percent(numerator, denominator).min(BigDecimal.valueOf(100).setScale(2, RoundingMode.UNNECESSARY));
    }

    public record CatalogImportScope(
            String serviceArea,
            SourceSystem sourceSystem,
            List<DatasetCode> datasets
    ) {
        public CatalogImportScope {
            datasets = List.copyOf(datasets);
        }
    }

    public record CatalogSourceRecord(
            String serviceArea,
            SourceSystem sourceSystem,
            DatasetCode datasetCode,
            String sourcePlaceId,
            ParkingPlaceType placeType,
            String displayName,
            BigDecimal latitude,
            BigDecimal longitude,
            EligibilityStatus carEligibleStatus,
            String rawNameHint,
            String rawLocationHint
    ) {
        private SourceKey sourceKey() {
            return new SourceKey(sourceSystem, datasetCode, sourcePlaceId == null ? "" : sourcePlaceId.trim());
        }

        private boolean hasValidCoordinates() {
            return latitude != null
                    && longitude != null
                    && latitude.compareTo(BigDecimal.valueOf(-90)) >= 0
                    && latitude.compareTo(BigDecimal.valueOf(90)) <= 0
                    && longitude.compareTo(BigDecimal.valueOf(-180)) >= 0
                    && longitude.compareTo(BigDecimal.valueOf(180)) <= 0;
        }

        private boolean isAcceptableCatalogCandidate() {
            return sourceKey().hasSourcePlaceId()
                    && hasValidCoordinates()
                    && carEligibleStatus == EligibilityStatus.CONFIRMED;
        }
    }

    public record CatalogImportDryRunReport(
            String serviceArea,
            SourceSystem sourceSystem,
            List<DatasetCode> datasets,
            int scopedRows,
            int acceptedCandidates,
            int missingSourceIds,
            int duplicateSourceIds,
            int invalidCoordinates,
            int unknownEligibility,
            long existingVerifiedMappings,
            BigDecimal acceptedCandidatePercent,
            BigDecimal verifiedMappingCoveragePercent,
            ImportRecommendation recommendation
    ) {
        public CatalogImportDryRunReport {
            datasets = List.copyOf(datasets);
        }
    }

    public enum ImportRecommendation {
        READY_FOR_IMPORT_REVIEW,
        REVIEW_ELIGIBILITY_RULES,
        REVIEW_SOURCE_ID_OR_COORDINATE_QUALITY,
        REJECT_NO_ACCEPTED_CANDIDATES
    }

    private record SourceKey(SourceSystem sourceSystem, DatasetCode datasetCode, String sourcePlaceId) {
        private boolean hasSourcePlaceId() {
            return sourcePlaceId != null && !sourcePlaceId.isBlank();
        }
    }
}
