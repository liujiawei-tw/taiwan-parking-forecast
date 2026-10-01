package parking.forecast.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.repository.ParkingPlaceRepository;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportDryRunReport;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;
import parking.forecast.service.CatalogImportDryRunService.ImportRecommendation;

@Service
public class CatalogImportService {

    private final ParkingPlaceRepository parkingPlaceRepository;
    private final SourceMappingRepository sourceMappingRepository;

    public CatalogImportService(
            ParkingPlaceRepository parkingPlaceRepository,
            SourceMappingRepository sourceMappingRepository
    ) {
        this.parkingPlaceRepository = parkingPlaceRepository;
        this.sourceMappingRepository = sourceMappingRepository;
    }

    @Transactional
    public CatalogImportResult importVerifiedMappings(
            CatalogImportDryRunReport report,
            List<CatalogSourceRecord> sourceRecords
    ) {
        Instant importedAt = Instant.now();
        if (report.recommendation() != ImportRecommendation.READY_FOR_IMPORT_REVIEW) {
            return CatalogImportResult.rejected(
                    report.serviceArea(),
                    CatalogImportStatus.REJECTED_DRY_RUN_NOT_READY,
                    report.existingVerifiedMappings(),
                    report.existingVerifiedMappings()
            );
        }

        int createdPlaces = 0;
        int createdMappings = 0;
        int skippedExistingMappings = 0;
        int rejectedCandidates = 0;

        for (CatalogSourceRecord record : sourceRecords) {
            if (!report.serviceArea().equals(record.serviceArea())) {
                continue;
            }
            if (!record.isAcceptableCatalogCandidate()) {
                rejectedCandidates++;
                continue;
            }

            String sourcePlaceId = record.sourcePlaceId().trim();
            boolean mappingExists = sourceMappingRepository
                    .findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                            record.sourceSystem(),
                            record.datasetCode(),
                            sourcePlaceId
                    )
                    .isPresent();
            if (mappingExists) {
                skippedExistingMappings++;
                continue;
            }

            ParkingPlaceEntity parkingPlace = createParkingPlace(record, importedAt);
            parkingPlaceRepository.save(parkingPlace);

            SourceMappingEntity sourceMapping = new SourceMappingEntity(
                    record.sourceSystem(),
                    record.datasetCode(),
                    sourcePlaceId,
                    parkingPlace,
                    MappingStatus.VERIFIED,
                    importedAt,
                    importedAt
            );
            sourceMapping.setValidFrom(importedAt);
            sourceMapping.setRawNameHint(record.rawNameHint());
            sourceMapping.setRawLocationHint(record.rawLocationHint());
            sourceMappingRepository.save(sourceMapping);

            createdPlaces++;
            createdMappings++;
        }

        long verifiedMappingsAfterImport = report.existingVerifiedMappings() + createdMappings;
        return new CatalogImportResult(
                report.serviceArea(),
                CatalogImportStatus.IMPORTED,
                createdPlaces,
                createdMappings,
                skippedExistingMappings,
                rejectedCandidates,
                report.existingVerifiedMappings(),
                verifiedMappingsAfterImport
        );
    }

    private ParkingPlaceEntity createParkingPlace(CatalogSourceRecord record, Instant importedAt) {
        ParkingPlaceEntity parkingPlace = new ParkingPlaceEntity(
                record.placeType(),
                record.displayName(),
                record.latitude(),
                record.longitude(),
                record.carEligibleStatus(),
                ActiveStatus.ACTIVE,
                record.serviceArea(),
                importedAt,
                importedAt
        );
        if (record.placeType() == ParkingPlaceType.OFF_STREET_LOT) {
            parkingPlace.setEntranceLatitude(record.latitude());
            parkingPlace.setEntranceLongitude(record.longitude());
        }
        return parkingPlace;
    }

    public record CatalogImportResult(
            String serviceArea,
            CatalogImportStatus status,
            int createdPlaces,
            int createdMappings,
            int skippedExistingMappings,
            int rejectedCandidates,
            long verifiedMappingsBeforeImport,
            long verifiedMappingsAfterImport
    ) {
        private static CatalogImportResult rejected(
                String serviceArea,
                CatalogImportStatus status,
                long verifiedMappingsBeforeImport,
                long verifiedMappingsAfterImport
        ) {
            return new CatalogImportResult(
                    serviceArea,
                    status,
                    0,
                    0,
                    0,
                    0,
                    verifiedMappingsBeforeImport,
                    verifiedMappingsAfterImport
            );
        }
    }

    public enum CatalogImportStatus {
        IMPORTED,
        REJECTED_DRY_RUN_NOT_READY
    }
}
