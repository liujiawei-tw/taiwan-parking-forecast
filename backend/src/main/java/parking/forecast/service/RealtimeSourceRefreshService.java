package parking.forecast.service;

import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.TriggerType;

public interface RealtimeSourceRefreshService {

    RefreshExecutionResult refresh(DatasetCode datasetCode, TriggerType triggerType);

    record RefreshExecutionResult(
            boolean published,
            Long ingestionRunId,
            Long snapshotId,
            int httpRequestCount,
            int normalizedRecordCount,
            String message
    ) {
    }
}
