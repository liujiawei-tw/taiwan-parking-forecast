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
import parking.forecast.service.RealtimeSourceRefreshService.RefreshStageTimings;

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
        long totalStartedNanos = System.nanoTime();
        Instant startedAt = clock.instant();
        List<NormalizedAvailabilityObservation> observations = new ArrayList<>();
        FetchMeasurement measurement = new FetchMeasurement();
        long httpNanos = 0;
        long normalizeNanos = 0;
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            long httpStartedNanos = System.nanoTime();
            var result = ntpcOpenDataClient.fetchRoadsideAvailabilityPage(page, properties.getPageSize());
            httpNanos += elapsedSince(httpStartedNanos);
            measurement.record(result.attempts(), result.responseBytes());
            var rows = result.rows();
            long normalizeStartedNanos = System.nanoTime();
            rows.stream()
                    .map(row -> normalizer.normalizeRoadside(row, fetchedAt))
                    .forEach(observations::add);
            normalizeNanos += elapsedSince(normalizeStartedNanos);
            // NTPC may cap effective page size below the requested size; only an empty
            // page confirms that the full source traversal has reached its end.
            if (rows.isEmpty()) {
                measurement.markCompleted();
                break;
            }
        }
        Instant completedAt = clock.instant();
        long measurementStartedNanos = System.nanoTime();
        SourceMeasurementSummary summary = measure(DatasetCode.NTPC_ROADSIDE_SPOT, startedAt, completedAt, measurement, observations);
        long measurementNanos = elapsedSince(measurementStartedNanos);
        requireCompletePageTraversal(summary);
        return publish(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                triggerType,
                startedAt,
                completedAt,
                summary,
                observations,
                httpNanos,
                normalizeNanos,
                measurementNanos,
                totalStartedNanos
        );
    }

    private RefreshExecutionResult refreshOffStreet(TriggerType triggerType) {
        long totalStartedNanos = System.nanoTime();
        Instant startedAt = clock.instant();
        List<NormalizedAvailabilityObservation> observations = new ArrayList<>();
        FetchMeasurement measurement = new FetchMeasurement();
        long httpNanos = 0;
        long normalizeNanos = 0;
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            long httpStartedNanos = System.nanoTime();
            var result = ntpcOpenDataClient.fetchOffStreetAvailabilityPage(page, properties.getPageSize());
            httpNanos += elapsedSince(httpStartedNanos);
            measurement.record(result.attempts(), result.responseBytes());
            var rows = result.rows();
            long normalizeStartedNanos = System.nanoTime();
            rows.stream()
                    .map(row -> normalizer.normalizeOffStreet(row, fetchedAt))
                    .forEach(observations::add);
            normalizeNanos += elapsedSince(normalizeStartedNanos);
            // NTPC may cap effective page size below the requested size; only an empty
            // page confirms that the full source traversal has reached its end.
            if (rows.isEmpty()) {
                measurement.markCompleted();
                break;
            }
        }
        Instant completedAt = clock.instant();
        long measurementStartedNanos = System.nanoTime();
        SourceMeasurementSummary summary = measure(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, startedAt, completedAt, measurement, observations);
        long measurementNanos = elapsedSince(measurementStartedNanos);
        requireCompletePageTraversal(summary);
        return publish(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                triggerType,
                startedAt,
                completedAt,
                summary,
                observations,
                httpNanos,
                normalizeNanos,
                measurementNanos,
                totalStartedNanos
        );
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
            List<NormalizedAvailabilityObservation> observations,
            long httpNanos,
            long normalizeNanos,
            long measurementNanos,
            long totalStartedNanos
    ) {
        long publishStartedNanos = System.nanoTime();
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
        long publishNanos = elapsedSince(publishStartedNanos);
        RefreshStageTimings stageTimings = stageTimings(
                httpNanos,
                normalizeNanos,
                measurementNanos,
                publishNanos,
                totalStartedNanos
        );
        return new RefreshExecutionResult(
                result.published(),
                result.ingestionRunId(),
                result.snapshotId(),
                summary.httpRequestCount(),
                summary.pageCount(),
                summary.responseBytes(),
                observations.size(),
                result.reason(),
                stageTimings
        );
    }

    private RefreshStageTimings stageTimings(
            long httpNanos,
            long normalizeNanos,
            long measurementNanos,
            long publishNanos,
            long totalStartedNanos
    ) {
        return new RefreshStageTimings(
                nanosToMillis(httpNanos),
                nanosToMillis(normalizeNanos),
                nanosToMillis(measurementNanos),
                nanosToMillis(publishNanos),
                nanosToMillis(elapsedSince(totalStartedNanos))
        );
    }

    private long elapsedSince(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000);
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
