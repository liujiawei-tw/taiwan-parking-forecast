package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import parking.forecast.domain.*;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.CatalogImportDryRunService.*;
import parking.forecast.service.NtpcCatalogDryRunService.*;

class CatalogSubsetReviewServiceTest {
    private final CatalogImportService importer = mock(CatalogImportService.class);
    private final CatalogImportDryRunService dryRun = new CatalogImportDryRunService(mock(SourceMappingRepository.class));
    private final CatalogSubsetReviewService service = new CatalogSubsetReviewService(new ObjectMapper(), dryRun, importer);
    private final CatalogImportScope scope = new CatalogImportScope("AREA", SourceSystem.NTPC_OPEN_DATA,
            List.of(DatasetCode.NTPC_ROADSIDE_SPOT));

    @Test
    void reviewedSubsetKeepsParentUnknownAndBindsBothSelectedAndExcludedRecords() {
        var confirmed = record("1", EligibilityStatus.CONFIRMED);
        var unknown = record("2", EligibilityStatus.UNKNOWN);
        var source = source(List.of(confirmed, unknown), true);
        var review = service.review(source);
        assertThat(source.report().recommendation()).isEqualTo(ImportRecommendation.REVIEW_ELIGIBILITY_RULES);
        assertThat(review.readyForReview()).isTrue();
        assertThat(review.selectedRecords()).containsExactly(confirmed);
        assertThat(review.classifications()).containsEntry("NTPC_ROADSIDE_SPOT:UNKNOWN", 1L);
        assertThat(service.review(source(List.of(unknown, confirmed), true)).manifestSha256())
                .isEqualTo(review.manifestSha256());
        assertThat(service.review(source(List.of(confirmed, record("3", EligibilityStatus.UNKNOWN)), true)).manifestSha256())
                .isNotEqualTo(review.manifestSha256());
        service.importReviewedSubset(source, review.manifestSha256());
        verify(importer).importVerifiedMappings(review.subsetReport(), review.selectedRecords());
    }

    @Test
    void rejectsWrongManifestAndIncompleteTraversalWithoutWriting() {
        var source = source(List.of(record("1", EligibilityStatus.CONFIRMED)), true);
        assertThatIllegalArgumentException().isThrownBy(() -> service.importReviewedSubset(source, ""));
        var incomplete = source(source.sourceRecords(), false);
        assertThatIllegalArgumentException().isThrownBy(() -> service.importReviewedSubset(
                incomplete, service.review(incomplete).manifestSha256()));
        verifyNoInteractions(importer);
    }

    @Test
    void rejectsDuplicateMissingIdsInvalidCoordinatesAndNoAcceptedRows() {
        var valid = record("1", EligibilityStatus.CONFIRMED);
        var invalid = new CatalogSourceRecord("AREA", SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT, "2", ParkingPlaceType.ON_STREET_SPOT,
                "bad", null, null, EligibilityStatus.UNKNOWN, "", "");
        for (var rows : List.of(List.of(valid, valid), List.of(valid, record("", EligibilityStatus.UNKNOWN)),
                List.of(valid, invalid), List.of(record("2", EligibilityStatus.UNKNOWN)))) {
            var source = source(rows, true);
            var review = service.review(source);
            assertThat(review.readyForReview()).isFalse();
            assertThatIllegalArgumentException().isThrownBy(() -> service.importReviewedSubset(source, review.manifestSha256()));
        }
        verifyNoInteractions(importer);
    }

    private NtpcCatalogDryRunResult source(List<CatalogSourceRecord> records, boolean complete) {
        var summary = new FetchSummary("test", 1, 1, 100, 1, complete);
        return new NtpcCatalogDryRunResult("AREA", dryRun.dryRun(scope, records), summary, summary, records);
    }

    private CatalogSourceRecord record(String id, EligibilityStatus status) {
        return new CatalogSourceRecord("AREA", SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT, id, ParkingPlaceType.ON_STREET_SPOT,
                "test", new BigDecimal("25"), new BigDecimal("121"), status, "", "");
    }
}
