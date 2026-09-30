package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportScope;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;
import parking.forecast.service.CatalogImportDryRunService.ImportRecommendation;

@ExtendWith(MockitoExtension.class)
class CatalogImportDryRunServiceTest {

    @Mock
    private SourceMappingRepository sourceMappingRepository;

    @Test
    void dryRunReportsAcceptedCandidatesAndQualityProblemsForAnyScope() {
        CatalogImportDryRunService service = new CatalogImportDryRunService(sourceMappingRepository);
        CatalogImportScope scope = new CatalogImportScope(
                "NEW_TAIPEI_XINZHUANG",
                SourceSystem.NTPC_OPEN_DATA,
                List.of(DatasetCode.NTPC_ROADSIDE_SPOT, DatasetCode.NTPC_OFF_STREET_AVAILABILITY)
        );
        when(sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                MappingStatus.VERIFIED
        )).thenReturn(7L);
        when(sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                MappingStatus.VERIFIED
        )).thenReturn(3L);

        var report = service.dryRun(scope, List.of(
                record("NEW_TAIPEI_XINZHUANG", DatasetCode.NTPC_ROADSIDE_SPOT, "R-1", EligibilityStatus.CONFIRMED, "25.1", "121.4"),
                record("NEW_TAIPEI_XINZHUANG", DatasetCode.NTPC_ROADSIDE_SPOT, "R-1", EligibilityStatus.CONFIRMED, "25.2", "121.5"),
                record("NEW_TAIPEI_XINZHUANG", DatasetCode.NTPC_ROADSIDE_SPOT, "", EligibilityStatus.CONFIRMED, "25.3", "121.6"),
                record("NEW_TAIPEI_XINZHUANG", DatasetCode.NTPC_ROADSIDE_SPOT, "R-4", EligibilityStatus.UNKNOWN, "25.4", "121.7"),
                record("NEW_TAIPEI_XINZHUANG", DatasetCode.NTPC_OFF_STREET_AVAILABILITY, "L-1", EligibilityStatus.CONFIRMED, "181", "121.8"),
                record("NEW_TAIPEI_BANQIAO", DatasetCode.NTPC_ROADSIDE_SPOT, "B-1", EligibilityStatus.CONFIRMED, "25.0", "121.4")
        ));

        assertThat(report.serviceArea()).isEqualTo("NEW_TAIPEI_XINZHUANG");
        assertThat(report.scopedRows()).isEqualTo(5);
        assertThat(report.acceptedCandidates()).isEqualTo(2);
        assertThat(report.missingSourceIds()).isEqualTo(1);
        assertThat(report.duplicateSourceIds()).isEqualTo(1);
        assertThat(report.invalidCoordinates()).isEqualTo(1);
        assertThat(report.unknownEligibility()).isEqualTo(1);
        assertThat(report.existingVerifiedMappings()).isEqualTo(10);
        assertThat(report.acceptedCandidatePercent()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(report.verifiedMappingCoveragePercent()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(report.recommendation()).isEqualTo(ImportRecommendation.REVIEW_SOURCE_ID_OR_COORDINATE_QUALITY);
    }

    @Test
    void dryRunCanApproveCleanScopeForImportReview() {
        CatalogImportDryRunService service = new CatalogImportDryRunService(sourceMappingRepository);
        CatalogImportScope scope = new CatalogImportScope(
                "NEW_TAIPEI_BANQIAO",
                SourceSystem.NTPC_OPEN_DATA,
                List.of(DatasetCode.NTPC_ROADSIDE_SPOT)
        );
        when(sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                MappingStatus.VERIFIED
        )).thenReturn(0L);

        var report = service.dryRun(scope, List.of(
                record("NEW_TAIPEI_BANQIAO", DatasetCode.NTPC_ROADSIDE_SPOT, "B-1", EligibilityStatus.CONFIRMED, "25.0", "121.4"),
                record("NEW_TAIPEI_BANQIAO", DatasetCode.NTPC_ROADSIDE_SPOT, "B-2", EligibilityStatus.CONFIRMED, "25.1", "121.5")
        ));

        assertThat(report.scopedRows()).isEqualTo(2);
        assertThat(report.acceptedCandidates()).isEqualTo(2);
        assertThat(report.recommendation()).isEqualTo(ImportRecommendation.READY_FOR_IMPORT_REVIEW);
    }

    private CatalogSourceRecord record(
            String serviceArea,
            DatasetCode datasetCode,
            String sourcePlaceId,
            EligibilityStatus eligibilityStatus,
            String latitude,
            String longitude
    ) {
        return new CatalogSourceRecord(
                serviceArea,
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                sourcePlaceId,
                datasetCode == DatasetCode.NTPC_ROADSIDE_SPOT ? ParkingPlaceType.ON_STREET_SPOT : ParkingPlaceType.OFF_STREET_LOT,
                "測試停車位置",
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                eligibilityStatus,
                "raw-name",
                "raw-location"
        );
    }
}
