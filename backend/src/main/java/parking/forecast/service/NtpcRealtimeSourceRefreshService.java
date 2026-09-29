package parking.forecast.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import parking.forecast.adapter.ntpc.NtpcAvailabilityNormalizer;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient;
import parking.forecast.config.RefreshControlProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.service.AvailabilitySnapshotPublishingService.PublishSnapshotCommand;
import parking.forecast.service.AvailabilitySnapshotPublishingService.PublishSnapshotResult;
import parking.forecast.service.SourceMeasurementService.SourceMeasurementCommand;
import parking.forecast.service.SourceMeasurementService.SourceMeasurementSummary;

@Service
public class NtpcRealtimeSourceRefreshService implements RealtimeSourceRefreshService {

    private final NtpcOpenDataClient ntpcOpenDataClient;
    private final NtpcAvailabilityNormalizer normalizer;
    private final AvailabilitySnapshotPublishingService publishingService;
    private final SourceMeasurementService sourceMeasurementService;
    private final RefreshControlProperties properties;
    private final Clock clock;

    @Autowired
    public NtpcRealtimeSourceRefreshService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcAvailabilityNormalizer normalizer,
            AvailabilitySnapshotPublishingService publishingService,
            SourceMeasurementService sourceMeasurementService,
            RefreshControlProperties properties
    ) {
        this(ntpcOpenDataClient, normalizer, publishingService, sourceMeasurementService, properties, Clock.systemUTC());
    }

    NtpcRealtimeSourceRefreshService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcAvailabilityNormalizer normalizer,
            AvailabilitySnapshotPublishingService publishingService,
            SourceMeasurementService sourceMeasurementService,
            RefreshControlProperties properties,
            Clock clock
    ) {
        this.ntpcOpenDataClient = ntpcOpenDataClient;
        this.normalizer = normalizer;
        this.publishingService = publishingService;
        this.sourceMeasurementService = sourceMeasurementService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RefreshExecutionResult refresh(DatasetCode datasetCode, TriggerType triggerType) {
        return switch (datasetCode) {
            case NTPC_ROADSIDE_SPOT -> refreshRoadside(triggerType);
            case NTPC_OFF_STREET_AVAILABILITY -> refreshOffStreet(triggerType);
            default -> throw new IllegalArgumentException("Unsupported refresh dataset: " + datasetCode);
        };
    }

    private RefreshExecutionResult refreshRoadside(TriggerType triggerType) {
        Instant startedAt = clock.instant();
        List<NormalizedAvailabilityObservation> observations = new ArrayList<>();
        FetchMeasurement measurement = new FetchMeasurement();
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            var result = ntpcOpenDataClient.fetchRoadsideAvailabilityPage(page, properties.getPageSize());
            measurement.record(result.attempts(), result.responseBytes());
            var rows = result.rows();
            rows.stream()
                    .map(row -> normalizer.normalizeRoadside(row, fetchedAt))
                    .forEach(observations::add);
            if (rows.size() < properties.getPageSize()) {
                measurement.markCompleted();
                break;
            }
        }
        Instant completedAt = clock.instant();
        SourceMeasurementSummary summary = measure(DatasetCode.NTPC_ROADSIDE_SPOT, startedAt, completedAt, measurement, observations);
        requireCompletePageTraversal(summary);
        return publish(DatasetCode.NTPC_ROADSIDE_SPOT, triggerType, startedAt, completedAt, summary, observations);
    }

    private RefreshExecutionResult refreshOffStreet(TriggerType triggerType) {
        Instant startedAt = clock.instant();
        List<NormalizedAvailabilityObservation> observations = new ArrayList<>();
        FetchMeasurement measurement = new FetchMeasurement();
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            var result = ntpcOpenDataClient.fetchOffStreetAvailabilityPage(page, properties.getPageSize());
            measurement.record(result.attempts(), result.responseBytes());
            var rows = result.rows();
            rows.stream()
                    .map(row -> normalizer.normalizeOffStreet(row, fetchedAt))
                    .forEach(observations::add);
            if (rows.size() < properties.getPageSize()) {
                measurement.markCompleted();
                break;
            }
        }
        Instant completedAt = clock.instant();
        SourceMeasurementSummary summary = measure(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, startedAt, completedAt, measurement, observations);
        requireCompletePageTraversal(summary);
        return publish(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, triggerType, startedAt, completedAt, summary, observations);
    }

    private SourceMeasurementSummary measure(
            DatasetCode datasetCode,
            Instant startedAt,
            Instant completedAt,
            FetchMeasurement measurement,
            List<NormalizedAvailabilityObservation> observations
    ) {
        return sourceMeasurementService.analyze(new SourceMeasurementCommand(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                startedAt,
                completedAt,
                properties.getPageSize(),
                properties.getMaxPages(),
                measurement.pageCount(),
                measurement.httpRequestCount(),
                measurement.responseBytes(),
                measurement.completed(),
                observations
        ));
    }

    private void requireCompletePageTraversal(SourceMeasurementSummary summary) {
        if (!summary.pageTraversalCompleted()) {
            throw new IllegalStateException(
                    "來源資料超過可處理頁數上限，拒絕發布不完整快照：%s pages=%d httpRequests=%d pageSize=%d maxPages=%d responseBytes=%d recommendation=%s"
                            .formatted(
                                    summary.datasetCode(),
                                    summary.pageCount(),
                                    summary.httpRequestCount(),
                                    summary.pageSize(),
                                    summary.maxPages(),
                                    summary.responseBytes(),
                                    summary.recommendation()
                            )
            );
        }
    }

    private RefreshExecutionResult publish(
            DatasetCode datasetCode,
            TriggerType triggerType,
            Instant startedAt,
            Instant completedAt,
            SourceMeasurementSummary summary,
            List<NormalizedAvailabilityObservation> observations
    ) {
        PublishSnapshotResult result = publishingService.publish(new PublishSnapshotCommand(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                triggerType,
                startedAt,
                completedAt,
                summary.httpRequestCount(),
                observations.size(),
                observations,
                summary.toQualitySummary()
        ));
        return new RefreshExecutionResult(
                result.published(),
                result.ingestionRunId(),
                result.snapshotId(),
                summary.httpRequestCount(),
                observations.size(),
                result.reason()
        );
    }

    private static class FetchMeasurement {
        private int pageCount;
        private int httpRequestCount;
        private long responseBytes;
        private boolean completed;

        void record(int attempts, long pageResponseBytes) {
            pageCount++;
            httpRequestCount += attempts;
            responseBytes += pageResponseBytes;
        }

        void markCompleted() {
            completed = true;
        }

        int pageCount() {
            return pageCount;
        }

        int httpRequestCount() {
            return httpRequestCount;
        }

        long responseBytes() {
            return responseBytes;
        }

        boolean completed() {
            return completed;
        }
    }
}
