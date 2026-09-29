package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;
import parking.forecast.config.RefreshControlProperties;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.TriggerType;

@ExtendWith(MockitoExtension.class)
class NtpcRealtimeSourceRefreshServiceTest {

    @Mock
    private NtpcOpenDataClient ntpcOpenDataClient;

    @Mock
    private NtpcAvailabilityNormalizer normalizer;

    @Mock
    private AvailabilitySnapshotPublishingService publishingService;

    @Test
    void refusesToPublishWhenConfiguredPageLimitWouldTruncateTheSource() {
        RefreshControlProperties properties = new RefreshControlProperties();
        properties.setPageSize(1);
        properties.setMaxPages(1);
        when(ntpcOpenDataClient.fetchRoadsideAvailability(0, 1))
                .thenReturn(List.of(roadsideRow()));
        var service = new NtpcRealtimeSourceRefreshService(
                ntpcOpenDataClient,
                normalizer,
                publishingService,
                properties,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.refresh(DatasetCode.NTPC_ROADSIDE_SPOT, TriggerType.MANUAL_REFRESH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒絕發布不完整快照");

        verifyNoInteractions(publishingService);
    }

    private NtpcRoadsideParkingAvailabilityDto roadsideRow() {
        return new NtpcRoadsideParkingAvailabilityDto(
                "1", "RA-001", "測試車格", null, null, null, null, null,
                null, "測試路", "0", null, null, null, null, null, "新莊區"
        );
    }
}
