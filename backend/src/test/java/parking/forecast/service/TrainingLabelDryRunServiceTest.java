package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.AvailabilityObservationRepository;
import parking.forecast.service.TrainingLabelDryRunService.CoverageStatus;
import parking.forecast.service.TrainingLabelDryRunService.LabelRejectReason;
import parking.forecast.service.TrainingLabelDryRunService.LabelTarget;
import parking.forecast.service.TrainingLabelDryRunService.OffStreetLotDryRunCommand;
import parking.forecast.service.TrainingLabelDryRunService.RoadsideAreaDryRunCommand;

@ExtendWith(MockitoExtension.class)
class TrainingLabelDryRunServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant FETCHED_AT = Instant.parse("2026-10-01T08:00:00Z");

    @Mock
    private AvailabilityObservationRepository observationRepository;

    private TrainingLabelDryRunService service;

    @BeforeEach
    void setUp() {
        service = new TrainingLabelDryRunService(observationRepository);
    }

    @Test
    void roadsideDryRunRejectsPartialCoverageBeforeProducingAreaLabel() {
        AvailabilitySnapshotEntity snapshot = publishedSnapshot(DatasetCode.NTPC_ROADSIDE_SPOT);
        AvailabilityObservationEntity available = observation(
                snapshot,
                place(101L, ParkingPlaceType.ON_STREET_SPOT, 25.0414, 121.4647),
                DatasetCode.NTPC_ROADSIDE_SPOT,
                AvailabilityStatus.AVAILABLE,
                QualityStatus.PASS
        );
        available.setAvailableSpot(true);
        when(observationRepository.findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
                SnapshotStatus.PUBLISHED,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                FROM,
                TO
        )).thenReturn(List.of(available));

        var report = service.dryRunRoadsideArea(new RoadsideAreaDryRunCommand(
                "NEW_TAIPEI_XINZHUANG",
                25.0414,
                121.4647,
                300,
                FROM,
                TO,
                Duration.ofMinutes(5),
                0.8,
                CoverageStatus.PARTIAL
        ));

        assertThat(report.target()).isEqualTo(LabelTarget.ROADSIDE_AREA);
        assertThat(report.usableSamples()).isZero();
        assertThat(report.rejectedSamples()).isEqualTo(1);
        assertThat(report.rejectionReasons())
                .containsEntry(LabelRejectReason.CATALOG_COVERAGE_PARTIAL, 1L);
    }

    @Test
    void roadsideDryRunProducesPositiveAreaLabelWhenCoverageAndObservationsAreUsable() {
        AvailabilitySnapshotEntity snapshot = publishedSnapshot(DatasetCode.NTPC_ROADSIDE_SPOT);
        AvailabilityObservationEntity available = observation(
                snapshot,
                place(101L, ParkingPlaceType.ON_STREET_SPOT, 25.0414, 121.4647),
                DatasetCode.NTPC_ROADSIDE_SPOT,
                AvailabilityStatus.AVAILABLE,
                QualityStatus.PASS
        );
        available.setAvailableSpot(true);
        AvailabilityObservationEntity occupied = observation(
                snapshot,
                place(102L, ParkingPlaceType.ON_STREET_SPOT, 25.0415, 121.4648),
                DatasetCode.NTPC_ROADSIDE_SPOT,
                AvailabilityStatus.OCCUPIED,
                QualityStatus.PASS
        );
        occupied.setAvailableSpot(false);
        when(observationRepository.findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
                SnapshotStatus.PUBLISHED,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                FROM,
                TO
        )).thenReturn(List.of(available, occupied));

        var report = service.dryRunRoadsideArea(new RoadsideAreaDryRunCommand(
                "NEW_TAIPEI_XINZHUANG",
                25.0414,
                121.4647,
                300,
                FROM,
                TO,
                Duration.ofMinutes(5),
                1.0,
                CoverageStatus.COMPLETE
        ));

        assertThat(report.candidateSamples()).isEqualTo(1);
        assertThat(report.usableSamples()).isEqualTo(1);
        assertThat(report.positiveLabels()).isEqualTo(1);
        assertThat(report.candidates().getFirst().validObservationRatio()).isEqualTo(1.0);
        verify(observationRepository).findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
                SnapshotStatus.PUBLISHED,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                FROM,
                TO
        );
    }

    @Test
    void offStreetDryRunSeparatesUsableAndRejectedLotSamples() {
        AvailabilitySnapshotEntity snapshot = publishedSnapshot(DatasetCode.NTPC_OFF_STREET_AVAILABILITY);
        AvailabilityObservationEntity usable = observation(
                snapshot,
                place(201L, ParkingPlaceType.OFF_STREET_LOT, 25.0414, 121.4647),
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                AvailabilityStatus.AVAILABLE,
                QualityStatus.PASS
        );
        usable.setAvailableCarSpaces(3);
        AvailabilityObservationEntity unknown = observation(
                snapshot,
                place(202L, ParkingPlaceType.OFF_STREET_LOT, 25.0416, 121.4649),
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                AvailabilityStatus.UNKNOWN,
                QualityStatus.PASS
        );
        unknown.setAvailableCarSpaces(null);
        AvailabilityObservationEntity failedQuality = observation(
                snapshot,
                place(203L, ParkingPlaceType.OFF_STREET_LOT, 25.0418, 121.4651),
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                AvailabilityStatus.AVAILABLE,
                QualityStatus.FAIL
        );
        failedQuality.setAvailableCarSpaces(8);
        when(observationRepository.findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
                SnapshotStatus.PUBLISHED,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                FROM,
                TO
        )).thenReturn(List.of(usable, unknown, failedQuality));

        var report = service.dryRunOffStreetLots(new OffStreetLotDryRunCommand(
                FROM,
                TO,
                Duration.ofMinutes(5)
        ));

        assertThat(report.target()).isEqualTo(LabelTarget.OFF_STREET_LOT);
        assertThat(report.candidateSamples()).isEqualTo(3);
        assertThat(report.usableSamples()).isEqualTo(1);
        assertThat(report.positiveLabels()).isEqualTo(1);
        assertThat(report.rejectionReasons())
                .containsEntry(LabelRejectReason.UNKNOWN_AVAILABILITY, 1L)
                .containsEntry(LabelRejectReason.QUALITY_NOT_USABLE, 1L);
    }

    private AvailabilitySnapshotEntity publishedSnapshot(DatasetCode datasetCode) {
        AvailabilitySnapshotEntity snapshot = new AvailabilitySnapshotEntity(
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                SnapshotStatus.PUBLISHED,
                FETCHED_AT.minusSeconds(5),
                QualityStatus.PASS
        );
        snapshot.setSystemFetchedAt(FETCHED_AT);
        snapshot.setPublishedAt(FETCHED_AT.plusSeconds(1));
        return snapshot;
    }

    private ParkingPlaceEntity place(Long id, ParkingPlaceType placeType, double latitude, double longitude) {
        ParkingPlaceEntity place = new ParkingPlaceEntity(
                placeType,
                "測試停車位置 " + id,
                BigDecimal.valueOf(latitude),
                BigDecimal.valueOf(longitude),
                EligibilityStatus.CONFIRMED,
                ActiveStatus.ACTIVE,
                "NEW_TAIPEI_XINZHUANG",
                FETCHED_AT.minusSeconds(60),
                FETCHED_AT.minusSeconds(60)
        );
        ReflectionTestUtils.setField(place, "id", id);
        return place;
    }

    private AvailabilityObservationEntity observation(
            AvailabilitySnapshotEntity snapshot,
            ParkingPlaceEntity parkingPlace,
            DatasetCode datasetCode,
            AvailabilityStatus availabilityStatus,
            QualityStatus qualityStatus
    ) {
        return new AvailabilityObservationEntity(
                snapshot,
                parkingPlace,
                SourceSystem.NTPC_OPEN_DATA,
                datasetCode,
                "source-" + parkingPlace.getId(),
                availabilityStatus,
                FETCHED_AT,
                qualityStatus,
                FETCHED_AT
        );
    }
}
