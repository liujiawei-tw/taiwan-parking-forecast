package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.DatasetCode;
import parking.forecast.service.CatalogCoverageReportService.CatalogCoverageReport;
import parking.forecast.service.CatalogCoverageReportService.CoverageStatus;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogCoverageReportServiceTest {

    @Autowired
    private CatalogCoverageReportService service;

    @Test
    void reportsCurrentXinzhuangSeedAsPartialCoverage() {
        CatalogCoverageReport report = service.buildXinzhuangReport();

        assertThat(report.serviceArea()).isEqualTo("NEW_TAIPEI_XINZHUANG");
        assertThat(report.lines()).hasSize(2);

        assertThat(report.lines())
                .filteredOn(line -> line.datasetCode() == DatasetCode.NTPC_ROADSIDE_SPOT)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.expectedOfficialRecords()).isEqualTo(2_124);
                    assertThat(line.catalogRecords()).isEqualTo(7);
                    assertThat(line.verifiedMappings()).isEqualTo(7);
                    assertThat(line.catalogCoveragePercent()).isEqualByComparingTo(new BigDecimal("0.33"));
                    assertThat(line.mappingCoveragePercent()).isEqualByComparingTo(new BigDecimal("0.33"));
                    assertThat(line.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
                });

        assertThat(report.lines())
                .filteredOn(line -> line.datasetCode() == DatasetCode.NTPC_OFF_STREET_AVAILABILITY)
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.expectedOfficialRecords()).isEqualTo(97);
                    assertThat(line.catalogRecords()).isEqualTo(3);
                    assertThat(line.verifiedMappings()).isEqualTo(3);
                    assertThat(line.catalogCoveragePercent()).isEqualByComparingTo(new BigDecimal("3.09"));
                    assertThat(line.mappingCoveragePercent()).isEqualByComparingTo(new BigDecimal("3.09"));
                    assertThat(line.coverageStatus()).isEqualTo(CoverageStatus.PARTIAL);
                });

        assertThat(report.gatingNotes())
                .anySatisfy(note -> assertThat(note).contains("must not be presented as complete"))
                .anySatisfy(note -> assertThat(note).contains("Do not output a complete 500m roadside area probability"));
    }
}
