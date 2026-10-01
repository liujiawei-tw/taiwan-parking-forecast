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
            int pageCount,
            long responseBytes,
            int normalizedRecordCount,
            String message,
            RefreshStageTimings stageTimings
    ) {
        public RefreshExecutionResult(
                boolean published,
                Long ingestionRunId,
                Long snapshotId,
                int httpRequestCount,
                int normalizedRecordCount,
                String message
        ) {
            this(
                    published,
                    ingestionRunId,
                    snapshotId,
                    httpRequestCount,
                    0,
                    0,
                    normalizedRecordCount,
                    message,
                    RefreshStageTimings.empty()
            );
        }
    }

    record RefreshStageTimings(
            long httpMillis,
            long normalizeMillis,
            long measurementMillis,
            long publishTransactionMillis,
            long totalMillis
    ) {
        static RefreshStageTimings empty() {
            return new RefreshStageTimings(0, 0, 0, 0, 0);
        }

        public String toSummary() {
            return "stageTimings{httpMs=%d, normalizeMs=%d, measurementMs=%d, publishTransactionMs=%d, totalMs=%d}"
                    .formatted(httpMillis, normalizeMillis, measurementMillis, publishTransactionMillis, totalMillis);
        }
    }
}
