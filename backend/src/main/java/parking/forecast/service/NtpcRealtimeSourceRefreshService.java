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

@Service
public class NtpcRealtimeSourceRefreshService implements RealtimeSourceRefreshService {

    private final NtpcOpenDataClient ntpcOpenDataClient;
    private final NtpcAvailabilityNormalizer normalizer;
    private final AvailabilitySnapshotPublishingService publishingService;
    private final RefreshControlProperties properties;
    private final Clock clock;

    @Autowired
    public NtpcRealtimeSourceRefreshService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcAvailabilityNormalizer normalizer,
            AvailabilitySnapshotPublishingService publishingService,
            RefreshControlProperties properties
    ) {
        this(ntpcOpenDataClient, normalizer, publishingService, properties, Clock.systemUTC());
    }

    NtpcRealtimeSourceRefreshService(
            NtpcOpenDataClient ntpcOpenDataClient,
            NtpcAvailabilityNormalizer normalizer,
            AvailabilitySnapshotPublishingService publishingService,
            RefreshControlProperties properties,
            Clock clock
    ) {
        this.ntpcOpenDataClient = ntpcOpenDataClient;
        this.normalizer = normalizer;
        this.publishingService = publishingService;
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
        int httpRequests = 0;
        boolean completed = false;
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            var rows = ntpcOpenDataClient.fetchRoadsideAvailability(page, properties.getPageSize());
            httpRequests++;
            rows.stream()
                    .map(row -> normalizer.normalizeRoadside(row, fetchedAt))
                    .forEach(observations::add);
            if (rows.size() < properties.getPageSize()) {
                completed = true;
                break;
            }
        }
        requireCompletePageTraversal(DatasetCode.NTPC_ROADSIDE_SPOT, completed);
        return publish(DatasetCode.NTPC_ROADSIDE_SPOT, triggerType, startedAt, httpRequests, observations);
    }

    private RefreshExecutionResult refreshOffStreet(TriggerType triggerType) {
        Instant startedAt = clock.instant();
        List<NormalizedAvailabilityObservation> observations = new ArrayList<>();
        int httpRequests = 0;
        boolean completed = false;
        for (int page = 0; page < properties.getMaxPages(); page++) {
            Instant fetchedAt = clock.instant();
            var rows = ntpcOpenDataClient.fetchOffStreetAvailability(page, properties.getPageSize());
            httpRequests++;
            rows.stream()
                    .map(row -> normalizer.normalizeOffStreet(row, fetchedAt))
                    .forEach(observations::add);
            if (rows.size() < properties.getPageSize()) {
                completed = true;
                break;
            }
        }
        requireCompletePageTraversal(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, completed);
        return publish(DatasetCode.NTPC_OFF_STREET_AVAILABILITY, triggerType, startedAt, httpRequests, observations);
    }

    private void requireCompletePageTraversal(DatasetCode datasetCode, boolean completed) {
        if (!completed) {
            throw new IllegalStateException("來源資料超過可處理頁數上限，拒絕發布不完整快照：" + datasetCode);
        }
    }

    private RefreshExecutionResult publish(
            DatasetCode datasetCode,
            TriggerType triggerType,
            Instant startedAt,
            int httpRequestCount,
            List<NormalizedAvailabilityObservation> observations
    ) {
        PublishSnapshotResult result = publishingService.publish(new PublishSnapshotCommand(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                triggerType,
                startedAt,
                clock.instant(),
                httpRequestCount,
                observations.size(),
                observations
        ));
        return new RefreshExecutionResult(
                result.published(),
                result.ingestionRunId(),
                result.snapshotId(),
                httpRequestCount,
                observations.size(),
                result.reason()
        );
    }
}
