package parking.forecast.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportScope;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportDryRunReport;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;
import parking.forecast.service.CatalogImportDryRunService.ImportRecommendation;
import parking.forecast.service.NtpcCatalogDryRunService.NtpcCatalogDryRunResult;

@Service
public class CatalogSubsetReviewService {
    public static final String RULE_VERSION = "ntpc-general-car-subset-v1";

    private final ObjectMapper objectMapper;
    private final CatalogImportDryRunService dryRunService;
    private final CatalogImportService importService;

    public CatalogSubsetReviewService(ObjectMapper objectMapper, CatalogImportDryRunService dryRunService,
                                     CatalogImportService importService) {
        this.objectMapper = objectMapper;
        this.dryRunService = dryRunService;
        this.importService = importService;
    }

    public Review review(NtpcCatalogDryRunResult source) {
        var parent = source.report();
        var ordered = source.sourceRecords().stream()
                .sorted(Comparator.comparing((CatalogSourceRecord row) -> row.datasetCode().name())
                        .thenComparing(row -> row.sourcePlaceId() == null ? "" : row.sourcePlaceId()))
                .toList();
        var selected = ordered.stream().filter(CatalogSourceRecord::isAcceptableCatalogCandidate).toList();
        Map<String, Long> classifications = new TreeMap<>();
        for (var row : ordered) {
            classifications.merge(row.datasetCode() + ":" + row.carEligibleStatus(), 1L, Long::sum);
        }
        var subsetReport = dryRunService.dryRun(new CatalogImportScope(
                source.serviceArea(), parent.sourceSystem(), parent.datasets()), selected);
        boolean sourceQualityReady = source.sourceTraversalComplete()
                && parent.missingSourceIds() == 0 && parent.duplicateSourceIds() == 0
                && parent.invalidCoordinates() == 0;
        return new Review(manifest(ordered), Map.copyOf(classifications), selected, subsetReport,
                sourceQualityReady && subsetReport.recommendation() == ImportRecommendation.READY_FOR_IMPORT_REVIEW);
    }

    public CatalogImportService.CatalogImportResult importReviewedSubset(
            NtpcCatalogDryRunResult source, String expectedManifest) {
        var review = review(source);
        if (!review.readyForReview() || !review.manifestSha256().equals(expectedManifest)) {
            throw new IllegalArgumentException("Reviewed subset requires complete source quality and matching manifest SHA-256");
        }
        return importService.importVerifiedMappings(review.subsetReport(), review.selectedRecords());
    }

    private String manifest(List<CatalogSourceRecord> ordered) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(RULE_VERSION.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(ordered)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Could not build catalog review manifest", exception);
        }
    }

    public record Review(String manifestSha256, Map<String, Long> classifications,
                         List<CatalogSourceRecord> selectedRecords, CatalogImportDryRunReport subsetReport,
                         boolean readyForReview) { }
}
