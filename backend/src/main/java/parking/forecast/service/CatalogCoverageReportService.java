package parking.forecast.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.ParkingPlaceRepository;
import parking.forecast.repository.SourceMappingRepository;

@Service
public class CatalogCoverageReportService {

    public static final String XINZHUANG_SERVICE_AREA = "NEW_TAIPEI_XINZHUANG";
    public static final int EXPECTED_XINZHUANG_ROADSIDE_SPOTS = 2_124;
    public static final int EXPECTED_XINZHUANG_OFF_STREET_LOTS = 97;
    public static final LocalDate BASELINE_MEASURED_ON = LocalDate.of(2026, 9, 29);

    private final ParkingPlaceRepository parkingPlaceRepository;
    private final SourceMappingRepository sourceMappingRepository;

    public CatalogCoverageReportService(
            ParkingPlaceRepository parkingPlaceRepository,
            SourceMappingRepository sourceMappingRepository
    ) {
        this.parkingPlaceRepository = parkingPlaceRepository;
        this.sourceMappingRepository = sourceMappingRepository;
    }

    public CatalogCoverageReport buildXinzhuangReport() {
        CoverageLine roadside = coverageLine(
                "roadside",
                ParkingPlaceType.ON_STREET_SPOT,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                EXPECTED_XINZHUANG_ROADSIDE_SPOTS
        );
        CoverageLine offStreet = coverageLine(
                "offStreet",
                ParkingPlaceType.OFF_STREET_LOT,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                EXPECTED_XINZHUANG_OFF_STREET_LOTS
        );

        return new CatalogCoverageReport(
                XINZHUANG_SERVICE_AREA,
                BASELINE_MEASURED_ON,
                List.of(roadside, offStreet),
                List.of(
                        "Current catalog is PARTIAL and must not be presented as complete Xinzhuang coverage.",
                        "Do not output a complete 300m roadside area probability until the roadside catalog boundary is complete or explicitly partial.",
                        "Full import needs repeatable source export, stable source IDs, car eligibility rules, and retired-record handling."
                )
        );
    }

    private CoverageLine coverageLine(
            String category,
            ParkingPlaceType placeType,
            DatasetCode datasetCode,
            int expectedOfficialRecords
    ) {
        long catalogRecords = parkingPlaceRepository.countByServiceAreaAndPlaceTypeAndActiveStatus(
                XINZHUANG_SERVICE_AREA,
                placeType,
                ActiveStatus.ACTIVE
        );
        long verifiedMappings = sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                MappingStatus.VERIFIED
        );

        return new CoverageLine(
                category,
                datasetCode,
                expectedOfficialRecords,
                catalogRecords,
                verifiedMappings,
                percent(catalogRecords, expectedOfficialRecords),
                percent(verifiedMappings, expectedOfficialRecords),
                expectedOfficialRecords == catalogRecords && expectedOfficialRecords == verifiedMappings
                        ? CoverageStatus.COMPLETE
                        : CoverageStatus.PARTIAL
        );
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    public record CatalogCoverageReport(
            String serviceArea,
            LocalDate baselineMeasuredOn,
            List<CoverageLine> lines,
            List<String> gatingNotes
    ) {
    }

    public record CoverageLine(
            String category,
            DatasetCode datasetCode,
            int expectedOfficialRecords,
            long catalogRecords,
            long verifiedMappings,
            BigDecimal catalogCoveragePercent,
            BigDecimal mappingCoveragePercent,
            CoverageStatus coverageStatus
    ) {
    }

    public enum CoverageStatus {
        COMPLETE,
        PARTIAL
    }
}
