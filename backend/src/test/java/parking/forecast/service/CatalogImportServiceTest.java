package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.ParkingPlaceRepository;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportDryRunReport;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;
import parking.forecast.service.CatalogImportDryRunService.ImportRecommendation;
import parking.forecast.service.CatalogImportService.CatalogImportStatus;

@ExtendWith(MockitoExtension.class)
class CatalogImportServiceTest {

    @Mock
    private ParkingPlaceRepository parkingPlaceRepository;

    @Mock
    private SourceMappingRepository sourceMappingRepository;

    @Test
    void importsOnlyAcceptedRecordsWithoutExistingMappings() {
        CatalogImportService service = new CatalogImportService(parkingPlaceRepository, sourceMappingRepository);
        CatalogSourceRecord roadside = record(DatasetCode.NTPC_ROADSIDE_SPOT, " R-1 ", ParkingPlaceType.ON_STREET_SPOT);
        CatalogSourceRecord offStreet = record(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, "P-1", ParkingPlaceType.OFF_STREET_LOT);
        CatalogSourceRecord existing = record(DatasetCode.NTPC_ROADSIDE_SPOT, "R-2", ParkingPlaceType.ON_STREET_SPOT);
        CatalogSourceRecord wrongArea = new CatalogSourceRecord(
                "NEW_TAIPEI_BANQIAO",
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "B-1",
                ParkingPlaceType.ON_STREET_SPOT,
                "板橋測試格位",
                new BigDecimal("25.0100000"),
                new BigDecimal("121.4600000"),
                EligibilityStatus.CONFIRMED,
                "raw-name",
                "raw-location"
        );

        when(sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "R-1"
        )).thenReturn(Optional.empty());
        when(sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                "P-1"
        )).thenReturn(Optional.empty());
        when(sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "R-2"
        )).thenReturn(Optional.of(existingMapping()));
        when(parkingPlaceRepository.save(any(ParkingPlaceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.importVerifiedMappings(
                readyReport(2),
                List.of(roadside, offStreet, existing, wrongArea)
        );

        assertThat(result.status()).isEqualTo(CatalogImportStatus.IMPORTED);
        assertThat(result.createdPlaces()).isEqualTo(2);
        assertThat(result.createdMappings()).isEqualTo(2);
        assertThat(result.skippedExistingMappings()).isEqualTo(1);
        assertThat(result.rejectedCandidates()).isZero();
        assertThat(result.verifiedMappingsBeforeImport()).isEqualTo(2);
        assertThat(result.verifiedMappingsAfterImport()).isEqualTo(4);

        ArgumentCaptor<ParkingPlaceEntity> parkingPlaceCaptor = ArgumentCaptor.forClass(ParkingPlaceEntity.class);
        verify(parkingPlaceRepository, times(2)).save(parkingPlaceCaptor.capture());
        assertThat(parkingPlaceCaptor.getAllValues())
                .extracting(ParkingPlaceEntity::getActiveStatus)
                .containsOnly(ActiveStatus.ACTIVE);
        assertThat(parkingPlaceCaptor.getAllValues().get(1).getEntranceLatitude())
                .isEqualByComparingTo(new BigDecimal("25.0372000"));
        ParkingPlaceEntity importedOffStreet = parkingPlaceCaptor.getAllValues().get(1);
        assertThat(importedOffStreet.getAddress()).isEqualTo("新北市新莊區中正路 1 號");
        assertThat(importedOffStreet.getParkingSpaceType()).isEqualTo("地下停車場");
        assertThat(importedOffStreet.getFeeDescription()).isEqualTo("小型車平日每小時 30 元");
        assertThat(importedOffStreet.getChargingDays()).isEqualTo("每日");
        assertThat(importedOffStreet.getChargingHours()).isEqualTo("00:00-24:00");
        assertThat(importedOffStreet.getTotalCarSpaces()).isEqualTo(120);
        assertThat(importedOffStreet.getCatalogNotes()).isEqualTo("鄰近捷運站");

        ArgumentCaptor<SourceMappingEntity> mappingCaptor = ArgumentCaptor.forClass(SourceMappingEntity.class);
        verify(sourceMappingRepository, times(2)).save(mappingCaptor.capture());
        assertThat(mappingCaptor.getAllValues())
                .extracting(SourceMappingEntity::getMappingStatus)
                .containsOnly(MappingStatus.VERIFIED);
        assertThat(mappingCaptor.getAllValues())
                .extracting(SourceMappingEntity::getSourcePlaceId)
                .containsExactly("R-1", "P-1");
        assertThat(mappingCaptor.getAllValues())
                .allSatisfy(mapping -> assertThat(mapping.getValidFrom()).isNotNull());
    }

    @Test
    void rejectsImportWhenDryRunIsNotReady() {
        CatalogImportService service = new CatalogImportService(parkingPlaceRepository, sourceMappingRepository);

        var result = service.importVerifiedMappings(
                new CatalogImportDryRunReport(
                        "NEW_TAIPEI_XINZHUANG",
                        SourceSystem.NTPC_OPEN_DATA,
                        List.of(DatasetCode.NTPC_ROADSIDE_SPOT),
                        1,
                        0,
                        1,
                        0,
                        0,
                        0,
                        5,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        ImportRecommendation.REVIEW_SOURCE_ID_OR_COORDINATE_QUALITY
                ),
                List.of(record(DatasetCode.NTPC_ROADSIDE_SPOT, "", ParkingPlaceType.ON_STREET_SPOT))
        );

        assertThat(result.status()).isEqualTo(CatalogImportStatus.REJECTED_DRY_RUN_NOT_READY);
        assertThat(result.createdPlaces()).isZero();
        assertThat(result.createdMappings()).isZero();
        assertThat(result.verifiedMappingsBeforeImport()).isEqualTo(5);
        assertThat(result.verifiedMappingsAfterImport()).isEqualTo(5);
        verify(parkingPlaceRepository, never()).save(any());
        verify(sourceMappingRepository, never()).save(any());
    }

    private CatalogImportDryRunReport readyReport(long existingVerifiedMappings) {
        return new CatalogImportDryRunReport(
                "NEW_TAIPEI_XINZHUANG",
                SourceSystem.NTPC_OPEN_DATA,
                List.of(DatasetCode.NTPC_ROADSIDE_SPOT, DatasetCode.NTPC_OFF_STREET_AVAILABILITY),
                3,
                3,
                0,
                0,
                0,
                0,
                existingVerifiedMappings,
                new BigDecimal("100.00"),
                new BigDecimal("66.67"),
                ImportRecommendation.READY_FOR_IMPORT_REVIEW
        );
    }

    private CatalogSourceRecord record(DatasetCode datasetCode, String sourcePlaceId, ParkingPlaceType placeType) {
        return new CatalogSourceRecord(
                "NEW_TAIPEI_XINZHUANG",
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                sourcePlaceId,
                placeType,
                "測試停車位置",
                new BigDecimal("25.0372000"),
                new BigDecimal("121.4525000"),
                EligibilityStatus.CONFIRMED,
                "raw-name",
                "raw-location",
                "新北市新莊區中正路 1 號",
                "地下停車場",
                "收費",
                "小型車平日每小時 30 元",
                "每日",
                "00:00-24:00",
                120,
                "鄰近捷運站"
        );
    }

    private SourceMappingEntity existingMapping() {
        ParkingPlaceEntity parkingPlace = new ParkingPlaceEntity(
                ParkingPlaceType.ON_STREET_SPOT,
                "既有格位",
                new BigDecimal("25.0372000"),
                new BigDecimal("121.4525000"),
                EligibilityStatus.CONFIRMED,
                ActiveStatus.ACTIVE,
                "NEW_TAIPEI_XINZHUANG",
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH
        );
        return new SourceMappingEntity(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "R-2",
                parkingPlace,
                MappingStatus.VERIFIED,
                java.time.Instant.EPOCH,
                java.time.Instant.EPOCH
        );
    }
}
