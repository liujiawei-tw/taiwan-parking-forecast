package parking.forecast.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import parking.forecast.config.NtpcCatalogImportCommandProperties;
import parking.forecast.service.CatalogImportService.CatalogImportResult;

@Component
@ConditionalOnProperty(prefix = "parking.catalog.ntpc.command", name = "enabled", havingValue = "true")
public class NtpcCatalogImportCommandRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(NtpcCatalogImportCommandRunner.class);

    private final NtpcCatalogDryRunService ntpcCatalogDryRunService;
    private final CatalogImportService catalogImportService;
    private final NtpcCatalogImportCommandProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    public NtpcCatalogImportCommandRunner(
            NtpcCatalogDryRunService ntpcCatalogDryRunService,
            CatalogImportService catalogImportService,
            NtpcCatalogImportCommandProperties properties,
            ConfigurableApplicationContext applicationContext
    ) {
        this.ntpcCatalogDryRunService = ntpcCatalogDryRunService;
        this.catalogImportService = catalogImportService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var dryRun = ntpcCatalogDryRunService.dryRun(properties.getServiceArea());
            LOGGER.info(
                    "NTPC catalog dry-run completed: serviceArea={}, scopedRows={}, acceptedCandidates={}, existingVerifiedMappings={}, recommendation={}, roadsidePages={}, roadsideHttpRequests={}, roadsideBytes={}, roadsideLatencyMs={}, offStreetPages={}, offStreetHttpRequests={}, offStreetBytes={}, offStreetLatencyMs={}, traversalComplete={}",
                    dryRun.serviceArea(),
                    dryRun.report().scopedRows(),
                    dryRun.report().acceptedCandidates(),
                    dryRun.report().existingVerifiedMappings(),
                    dryRun.report().recommendation(),
                    dryRun.roadside().pages(),
                    dryRun.roadside().httpRequests(),
                    dryRun.roadside().responseBytes(),
                    dryRun.roadside().elapsedMillis(),
                    dryRun.offStreetCatalog().pages(),
                    dryRun.offStreetCatalog().httpRequests(),
                    dryRun.offStreetCatalog().responseBytes(),
                    dryRun.offStreetCatalog().elapsedMillis(),
                    dryRun.sourceTraversalComplete()
            );

            if (!properties.isConfirmWrite()) {
                LOGGER.info("NTPC catalog import write skipped because confirm-write=false");
                return;
            }
            if (!dryRun.sourceTraversalComplete()) {
                LOGGER.warn("NTPC catalog import write skipped because source traversal is incomplete");
                return;
            }

            CatalogImportResult importResult = catalogImportService.importVerifiedMappings(
                    dryRun.report(),
                    dryRun.sourceRecords()
            );
            LOGGER.info(
                    "NTPC catalog import completed: serviceArea={}, status={}, createdPlaces={}, createdMappings={}, skippedExistingMappings={}, rejectedCandidates={}, verifiedMappingsBeforeImport={}, verifiedMappingsAfterImport={}",
                    importResult.serviceArea(),
                    importResult.status(),
                    importResult.createdPlaces(),
                    importResult.createdMappings(),
                    importResult.skippedExistingMappings(),
                    importResult.rejectedCandidates(),
                    importResult.verifiedMappingsBeforeImport(),
                    importResult.verifiedMappingsAfterImport()
            );
        } finally {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }
}
