package parking.forecast.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import parking.forecast.adapter.ntpc.NtpcCatalogRecordMapper;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient.NtpcOpenDataPage;
import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingLotDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.config.NtpcCatalogImportProperties;
import parking.forecast.config.NtpcCatalogImportProperties.Scope;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.SourceSystem;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportScope;
import parking.forecast.service.CatalogImportDryRunService.CatalogSourceRecord;
import parking.forecast.service.CatalogImportDryRunService.CatalogImportDryRunReport;

@Service
public class NtpcCatalogDryRunService {

    private final NtpcOpenDataClient ntpcOpenDataClient;
    private final NtpcCatalogRecordMapper ntpcCatalogRecordMapper;
    private final CatalogImportDryRunService catalogImportDryRunService;
    private final NtpcCatalogImportProperties properties;

    public NtpcCatalogDryRunService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcCatalogRecordMapper ntpcCatalogRecordMapper,
            CatalogImportDryRunService catalogImportDryRunService,
            NtpcCatalogImportProperties properties
    ) {
        this.ntpcOpenDataClient = ntpcOpenDataClient;
        this.ntpcCatalogRecordMapper = ntpcCatalogRecordMapper;
        this.catalogImportDryRunService = catalogImportDryRunService;
        this.properties = properties;
    }

    public List<NtpcCatalogDryRunResult> dryRunConfiguredScopes() {
        return properties.getScopes().stream()
                .map(this::dryRun)
                .toList();
    }

    public NtpcCatalogDryRunResult dryRun(String serviceArea) {
        Scope scope = properties.getScopes().stream()
                .filter(candidate -> candidate.getServiceArea().equals(serviceArea))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown NTPC catalog import serviceArea: %s".formatted(serviceArea)));
        return dryRun(scope);
    }

    private NtpcCatalogDryRunResult dryRun(Scope scope) {
        var roadsideRows = fetchRoadsideRows();
        var offStreetRows = fetchOffStreetCatalogRows();
        List<CatalogSourceRecord> sourceRecords = new ArrayList<>();
        sourceRecords.addAll(ntpcCatalogRecordMapper.toRoadsideCatalogRecords(
                scope.getServiceArea(),
                scope.getRoadsideAreaCode(),
                roadsideRows.rows()
        ));
        sourceRecords.addAll(ntpcCatalogRecordMapper.toOffStreetCatalogRecords(
                scope.getServiceArea(),
                scope.getOffStreetAreaName(),
                offStreetRows.rows()
        ));

        var importScope = new CatalogImportScope(
                scope.getServiceArea(),
                SourceSystem.NTPC_OPEN_DATA,
                List.of(DatasetCode.NTPC_ROADSIDE_SPOT, DatasetCode.NTPC_OFF_STREET_AVAILABILITY)
        );
        return new NtpcCatalogDryRunResult(
                scope.getServiceArea(),
                catalogImportDryRunService.dryRun(importScope, sourceRecords),
                roadsideRows.summary(),
                offStreetRows.summary(),
                sourceRecords
        );
    }

    private FetchedRows<NtpcRoadsideParkingAvailabilityDto> fetchRoadsideRows() {
        return fetchRows(
                "NTPC_ROADSIDE_SPOT",
                (page, size) -> ntpcOpenDataClient.fetchRoadsideAvailabilityPage(page, size)
        );
    }

    private FetchedRows<NtpcOffStreetParkingLotDto> fetchOffStreetCatalogRows() {
        return fetchRows(
                "NTPC_OFF_STREET_CATALOG",
                (page, size) -> ntpcOpenDataClient.fetchOffStreetCatalogPage(page, size)
        );
    }

    private <T> FetchedRows<T> fetchRows(String sourceName, PageFetcher<T> fetcher) {
        List<T> rows = new ArrayList<>();
        int pages = 0;
        int httpRequests = 0;
        long responseBytes = 0;
        long elapsedMillis = 0;
        boolean complete = true;

        for (int page = 0; page < properties.getMaxPages(); page++) {
            NtpcOpenDataPage<T> sourcePage = fetcher.fetch(page, properties.getPageSize());
            pages++;
            httpRequests += sourcePage.attempts();
            responseBytes += sourcePage.responseBytes();
            elapsedMillis += sourcePage.elapsedMillis();
            rows.addAll(sourcePage.rows());

            if (sourcePage.rows().size() < properties.getPageSize()) {
                return new FetchedRows<>(
                        List.copyOf(rows),
                        new FetchSummary(sourceName, pages, httpRequests, responseBytes, elapsedMillis, true)
                );
            }
        }

        complete = false;
        return new FetchedRows<>(
                List.copyOf(rows),
                new FetchSummary(sourceName, pages, httpRequests, responseBytes, elapsedMillis, complete)
        );
    }

    public record NtpcCatalogDryRunResult(
            String serviceArea,
            CatalogImportDryRunReport report,
            FetchSummary roadside,
            FetchSummary offStreetCatalog,
            List<CatalogSourceRecord> sourceRecords
    ) {
        public NtpcCatalogDryRunResult {
            sourceRecords = List.copyOf(sourceRecords);
        }

        public boolean sourceTraversalComplete() {
            return roadside.complete() && offStreetCatalog.complete();
        }
    }

    public record FetchSummary(
            String sourceName,
            int pages,
            int httpRequests,
            long responseBytes,
            long elapsedMillis,
            boolean complete
    ) {
    }

    private record FetchedRows<T>(List<T> rows, FetchSummary summary) {
    }

    @FunctionalInterface
    private interface PageFetcher<T> {
        NtpcOpenDataPage<T> fetch(int page, int size);
    }
}
