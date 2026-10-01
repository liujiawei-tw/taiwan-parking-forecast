package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import parking.forecast.adapter.ntpc.NtpcAvailabilityNormalizer;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient;
import parking.forecast.adapter.ntpc.NtpcOpenDataClient.NtpcOpenDataPage;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.config.RefreshControlProperties;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.NormalizedAvailabilityObservation;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.service.AvailabilitySnapshotPublishingService.PublishSnapshotResult;
import parking.forecast.service.SourceMeasurementService.SourceMeasurementSummary;

@ExtendWith(MockitoExtension.class)
class NtpcRealtimeSourceRefreshServiceTest {

    @Mock
    private NtpcOpenDataClient ntpcOpenDataClient;

    @Mock
    private NtpcAvailabilityNormalizer normalizer;

    @Mock
    private AvailabilitySnapshotPublishingService publishingService;

    @Mock
    private SourceMeasurementService sourceMeasurementService;

    @Test
    void returnsStageTimingsWhenRefreshPublishesSnapshot() {
        RefreshControlProperties properties = new RefreshControlProperties();
        properties.setPageSize(2);
        properties.setMaxPages(2);
        when(ntpcOpenDataClient.fetchRoadsideAvailabilityPage(0, 2))
                .thenReturn(new NtpcOpenDataPage<>(List.of(roadsideRow()), 0, 2, 1, 512, 20));
        when(normalizer.normalizeRoadside(roadsideRow(), Instant.parse("2026-09-29T08:00:00Z")))
                .thenReturn(observation());
        when(sourceMeasurementService.analyze(any()))
                .thenReturn(completeSummary());
        when(publishingService.publish(any()))
                .thenReturn(new PublishSnapshotResult(10L, 20L, true, QualityStatus.PASS, 1, 0, "published"));
        var service = new NtpcRealtimeSourceRefreshService(
                ntpcOpenDataClient,
                normalizer,
                publishingService,
                sourceMeasurementService,
                properties,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH);

        assertThat(result.published()).isTrue();
        assertThat(result.ingestionRunId()).isEqualTo(10L);
        assertThat(result.snapshotId()).isEqualTo(20L);
        assertThat(result.httpRequestCount()).isEqualTo(1);
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.responseBytes()).isEqualTo(512);
        assertThat(result.normalizedRecordCount()).isEqualTo(1);
        assertThat(result.stageTimings().httpMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.stageTimings().normalizeMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.stageTimings().measurementMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.stageTimings().publishTransactionMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.stageTimings().totalMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void refusesToPublishWhenConfiguredPageLimitWouldTruncateTheSource() {
        RefreshControlProperties properties = new RefreshControlProperties();
        properties.setPageSize(1);
        properties.setMaxPages(1);
        when(ntpcOpenDataClient.fetchRoadsideAvailabilityPage(0, 1))
                .thenReturn(new NtpcOpenDataPage<>(List.of(roadsideRow()), 0, 1, 1, 512, 20));
        when(normalizer.normalizeRoadside(roadsideRow(), Instant.parse("2026-09-29T08:00:00Z")))
                .thenReturn(observation());
        when(sourceMeasurementService.analyze(any()))
                .thenReturn(incompleteSummary());
        var service = new NtpcRealtimeSourceRefreshService(
                ntpcOpenDataClient,
                normalizer,
                publishingService,
                sourceMeasurementService,
                properties,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒絕發布不完整快照")
                .hasMessageContaining("httpRequests=1")
                .hasMessageContaining("responseBytes=512");

        verifyNoInteractions(publishingService);
    }

    private SourceMeasurementSummary completeSummary() {
        return new SourceMeasurementSummary(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                Instant.parse("2026-09-29T08:00:00Z"),
                Instant.parse("2026-09-29T08:00:00Z"),
                0,
                2,
                2,
                1,
                1,
                0,
                512,
                true,
                1,
                1,
                1,
                0,
                0,
                List.of(),
                1,
                1,
                0,
                0,
                "PUBLISH_MEETS_CURRENT_MVP_THRESHOLD"
        );
    }

    private SourceMeasurementSummary incompleteSummary() {
        return new SourceMeasurementSummary(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                Instant.parse("2026-09-29T08:00:00Z"),
                Instant.parse("2026-09-29T08:00:00Z"),
                0,
                1,
                1,
                1,
                1,
                0,
                512,
                false,
                1,
                1,
                1,
                0,
                0,
                List.of(),
                0,
                0,
                1,
                0,
                "REJECT_INCOMPLETE_PAGE_TRAVERSAL"
        );
    }

    private NtpcRoadsideParkingAvailabilityDto roadsideRow() {
        return new NtpcRoadsideParkingAvailabilityDto(
                "1", "RA-001", "測試車格", null, null, null, null, null,
                null, "測試路", "0", null, null, null, null, null, "新莊區"
        );
    }

    private NormalizedAvailabilityObservation observation() {
        return NormalizedAvailabilityObservation.builder()
                .sourceSystem(SourceSystem.NTPC_OPEN_DATA)
                .datasetCode(DatasetCode.NTPC_ROADSIDE_SPOT)
                .sourcePlaceId("1")
                .availabilityStatus(AvailabilityStatus.AVAILABLE)
                .qualityStatus(QualityStatus.PASS)
                .build();
    }
}
