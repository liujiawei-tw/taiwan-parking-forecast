package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import parking.forecast.adapter.ntpc.NtpcCatalogRecordMapper;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient.NtpcOpenDataPage;
import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingLotDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.config.NtpcCatalogImportProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.CatalogImportDryRunService.ImportRecommendation;

@ExtendWith(MockitoExtension.class)
class NtpcCatalogDryRunServiceTest {

    @Mock
    private NtpcOpenDataClient ntpcOpenDataClient;

    @Mock
    private SourceMappingRepository sourceMappingRepository;

    private NtpcCatalogImportProperties properties;
    private NtpcCatalogDryRunService service;

    @BeforeEach
    void setUp() {
        properties = new NtpcCatalogImportProperties();
        properties.setPageSize(2);
        properties.setMaxPages(3);
        service = new NtpcCatalogDryRunService(
                ntpcOpenDataClient,
                new NtpcCatalogRecordMapper(),
                new CatalogImportDryRunService(sourceMappingRepository),
                properties
        );
    }

    @Test
    void dryRunConfiguredScopeFetchesSourcesMapsRecordsAndKeepsFetchMeasurements() {
        when(ntpcOpenDataClient.fetchRoadsideAvailabilityPage(0, 2))
                .thenReturn(new NtpcOpenDataPage<>(List.of(
                        roadside("R-1", "CELL-1", "65000050", "新莊路", "25.0372", "121.4525", "小型車"),
                        roadside("R-2", "CELL-2", "65000010", "板橋路", "25.0100", "121.4600", "小型車")
                ), 0, 2, 1, 100, 10));
        when(ntpcOpenDataClient.fetchRoadsideAvailabilityPage(1, 2))
                .thenReturn(new NtpcOpenDataPage<>(List.of(), 1, 2, 1, 2, 4));
        when(ntpcOpenDataClient.fetchOffStreetCatalogPage(0, 2))
                .thenReturn(new NtpcOpenDataPage<>(List.of(
                        offStreet("P-1", "新莊區", "新莊停車場", "296239.5", "2770049", "100")
                ), 0, 2, 1, 80, 8));
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

        var result = service.dryRun("NEW_TAIPEI_XINZHUANG");

        assertThat(result.serviceArea()).isEqualTo("NEW_TAIPEI_XINZHUANG");
        assertThat(result.sourceTraversalComplete()).isTrue();
        assertThat(result.roadside().pages()).isEqualTo(2);
        assertThat(result.roadside().httpRequests()).isEqualTo(2);
        assertThat(result.roadside().responseBytes()).isEqualTo(102);
        assertThat(result.roadside().elapsedMillis()).isEqualTo(14);
        assertThat(result.offStreetCatalog().pages()).isEqualTo(1);
        assertThat(result.offStreetCatalog().httpRequests()).isEqualTo(1);
        assertThat(result.report().scopedRows()).isEqualTo(2);
        assertThat(result.report().acceptedCandidates()).isEqualTo(2);
        assertThat(result.report().existingVerifiedMappings()).isEqualTo(10);
        assertThat(result.report().recommendation()).isEqualTo(ImportRecommendation.READY_FOR_IMPORT_REVIEW);
    }

    @Test
    void marksTraversalIncompleteWhenPageLimitIsReached() {
        properties.setPageSize(1);
        properties.setMaxPages(1);
        when(ntpcOpenDataClient.fetchRoadsideAvailabilityPage(0, 1))
                .thenReturn(new NtpcOpenDataPage<>(List.of(
                        roadside("R-1", "CELL-1", "65000050", "新莊路", "25.0372", "121.4525", "小型車")
                ), 0, 1, 1, 100, 10));
        when(ntpcOpenDataClient.fetchOffStreetCatalogPage(0, 1))
                .thenReturn(new NtpcOpenDataPage<>(List.of(
                        offStreet("P-1", "新莊區", "新莊停車場", "296239.5", "2770049", "100")
                ), 0, 1, 1, 80, 8));
        when(sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                MappingStatus.VERIFIED
        )).thenReturn(0L);
        when(sourceMappingRepository.countBySourceSystemAndDatasetCodeAndMappingStatus(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                MappingStatus.VERIFIED
        )).thenReturn(0L);

        var result = service.dryRunConfiguredScopes().getFirst();

        assertThat(result.sourceTraversalComplete()).isFalse();
        assertThat(result.roadside().complete()).isFalse();
        assertThat(result.offStreetCatalog().complete()).isFalse();
        assertThat(result.report().acceptedCandidates()).isEqualTo(2);
        verify(ntpcOpenDataClient).fetchRoadsideAvailabilityPage(0, 1);
        verify(ntpcOpenDataClient).fetchOffStreetCatalogPage(0, 1);
    }

    @Test
    void rejectsUnknownServiceArea() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.dryRun("NEW_TAIPEI_BANQIAO"))
                .withMessageContaining("Unknown NTPC catalog import serviceArea");
    }

    private NtpcRoadsideParkingAvailabilityDto roadside(
            String id,
            String cellId,
            String areaCode,
            String roadName,
            String latitude,
            String longitude,
            String eligibilityText
    ) {
        return new NtpcRoadsideParkingAvailabilityDto(
                id,
                cellId,
                eligibilityText,
                "1-5",
                "08-20",
                "",
                eligibilityText,
                eligibilityText,
                "R-1",
                roadName,
                "0",
                "Y",
                "0",
                latitude,
                longitude,
                "65000",
                areaCode
        );
    }

    private NtpcOffStreetParkingLotDto offStreet(
            String id,
            String area,
            String name,
            String tw97x,
            String tw97y,
            String totalCar
    ) {
        return new NtpcOffStreetParkingLotDto(
                id,
                area,
                name,
                "新北市%s測試路1號".formatted(area),
                tw97x,
                tw97y,
                totalCar
        );
    }
}
