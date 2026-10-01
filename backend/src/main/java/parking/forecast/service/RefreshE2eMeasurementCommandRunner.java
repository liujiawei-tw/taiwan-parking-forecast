package parking.forecast.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import parking.forecast.config.RefreshE2eMeasurementCommandProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.repository.SourceMappingRepository;

@Component
@ConditionalOnProperty(prefix = "parking.refresh.measurement.command", name = "enabled", havingValue = "true")
public class RefreshE2eMeasurementCommandRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshE2eMeasurementCommandRunner.class);

    private final RealtimeSourceRefreshService realtimeSourceRefreshService;
    private final PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService;
    private final SourceMappingRepository sourceMappingRepository;
    private final RefreshE2eMeasurementCommandProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    public RefreshE2eMeasurementCommandRunner(
            RealtimeSourceRefreshService realtimeSourceRefreshService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceMappingRepository sourceMappingRepository,
            RefreshE2eMeasurementCommandProperties properties,
            ConfigurableApplicationContext applicationContext
    ) {
        this.realtimeSourceRefreshService = realtimeSourceRefreshService;
        this.publishedAvailabilitySnapshotService = publishedAvailabilitySnapshotService;
        this.sourceMappingRepository = sourceMappingRepository;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (DatasetCode datasetCode : properties.getDatasets()) {
                measure(datasetCode);
            }
        } finally {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }

    private void measure(DatasetCode datasetCode) {
        var refreshResult = realtimeSourceRefreshService.refresh(datasetCode, TriggerType.MANUAL_REFRESH);
        List<Long> parkingPlaceIds = sourceMappingRepository.findBySourceSystemAndDatasetCodeAndMappingStatus(
                        SourceSystem.NTPC_OPEN_DATA,
                        datasetCode,
                        MappingStatus.VERIFIED
                ).stream()
                .map(mapping -> mapping.getParkingPlace().getId())
                .toList();

        long queryReadStartedNanos = System.nanoTime();
        var view = publishedAvailabilitySnapshotService.findLatest(datasetCode, parkingPlaceIds);
        long queryReadMillis = Math.max(0, (System.nanoTime() - queryReadStartedNanos) / 1_000_000);

        LOGGER.info(
                "refreshE2eMeasurement{dataset={}, published={}, ingestionRunId={}, snapshotId={}, httpRequests={}, pages={}, responseBytes={}, normalizedRecords={}, message={}, {}, verifiedParkingPlaceIds={}, queryReadMs={}, queryStatus={}, queryObservations={}}",
                datasetCode,
                refreshResult.published(),
                refreshResult.ingestionRunId(),
                refreshResult.snapshotId(),
                refreshResult.httpRequestCount(),
                refreshResult.pageCount(),
                refreshResult.responseBytes(),
                refreshResult.normalizedRecordCount(),
                refreshResult.message(),
                refreshResult.stageTimings().toSummary(),
                parkingPlaceIds.size(),
                queryReadMillis,
                view.status(),
                view.observations().size()
        );
    }
}
