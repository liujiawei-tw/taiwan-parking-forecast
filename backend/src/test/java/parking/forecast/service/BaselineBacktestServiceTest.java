package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import parking.forecast.service.BaselineBacktestService.BacktestCommand;
import parking.forecast.service.TrainingLabelDryRunService.LabelCandidate;
import parking.forecast.service.TrainingLabelDryRunService.LabelDryRunReport;
import parking.forecast.service.TrainingLabelDryRunService.LabelTarget;

class BaselineBacktestServiceTest {

    private static final BacktestCommand COMMAND = new BacktestCommand(
            0.5,
            0.25,
            8,
            4,
            2,
            0.30
    );

    private BaselineBacktestService service;

    @BeforeEach
    void setUp() {
        service = new BaselineBacktestService();
    }

    @Test
    void backtestProducesTimeSplitAndBaselineMetricsForRoadsideLabels() {
        LabelDryRunReport labels = labelReport(
                LabelTarget.ROADSIDE_AREA,
                List.of(
                        candidate(0, null, true),
                        candidate(1, null, true),
                        candidate(2, null, false),
                        candidate(3, null, true),
                        candidate(4, null, false),
                        candidate(5, null, false),
                        candidate(6, null, true),
                        candidate(7, null, false)
                ),
                1
        );

        var report = service.backtest(labels, COMMAND);

        assertThat(report.target()).isEqualTo(LabelTarget.ROADSIDE_AREA);
        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.usableSamples()).isEqualTo(8);
        assertThat(report.rejectedLabelSamples()).isEqualTo(1);
        assertThat(report.timeSplit().trainingSamples()).isEqualTo(4);
        assertThat(report.timeSplit().validationSamples()).isEqualTo(2);
        assertThat(report.timeSplit().testSamples()).isEqualTo(2);
        assertThat(report.baselines())
                .extracting(BaselineBacktestService.BaselineDefinition::name)
                .containsExactly("TRAIN_POSITIVE_RATE", "TRAIN_MAJORITY_CLASS", "LATEST_TRAINING_LABEL");
        assertThat(report.evaluations()).hasSize(6);
        assertThat(report.evaluations())
                .filteredOn(evaluation -> "TRAIN_POSITIVE_RATE".equals(evaluation.baselineName())
                        && "TEST".equals(evaluation.splitName()))
                .singleElement()
                .satisfies(evaluation -> {
                    assertThat(evaluation.samples()).isEqualTo(2);
                    assertThat(evaluation.brierScore()).isBetween(0.0, 1.0);
                    assertThat(evaluation.logLoss()).isGreaterThanOrEqualTo(0.0);
                });
        assertThat(report.acceptance().status())
                .isIn("BASELINE_CANDIDATE", "INSUFFICIENT_DATA");
    }

    @Test
    void backtestUsesSameParkingLotLatestTrainingLabelForOffStreetPersistenceBaseline() {
        LabelDryRunReport labels = labelReport(
                LabelTarget.OFF_STREET_LOT,
                List.of(
                        candidate(0, 201L, false),
                        candidate(1, 202L, true),
                        candidate(2, 201L, true),
                        candidate(3, 202L, true),
                        candidate(4, 201L, true),
                        candidate(5, 202L, false),
                        candidate(6, 201L, true),
                        candidate(7, 202L, false)
                ),
                0
        );

        var report = service.backtest(labels, COMMAND);

        assertThat(report.target()).isEqualTo(LabelTarget.OFF_STREET_LOT);
        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.evaluations())
                .filteredOn(evaluation -> "LATEST_TRAINING_LABEL".equals(evaluation.baselineName())
                        && "TEST".equals(evaluation.splitName()))
                .singleElement()
                .satisfies(evaluation -> {
                    assertThat(evaluation.predictedPositive()).isEqualTo(2);
                    assertThat(evaluation.truePositive()).isEqualTo(1);
                    assertThat(evaluation.falsePositive()).isEqualTo(1);
                });
    }

    @Test
    void backtestKeepsInsufficientDataWhenUsableLabelsAreTooFew() {
        LabelDryRunReport labels = labelReport(
                LabelTarget.OFF_STREET_LOT,
                List.of(
                        candidate(0, 201L, true),
                        candidate(1, 201L, false),
                        candidate(2, 201L, true)
                ),
                2
        );

        var report = service.backtest(labels, COMMAND);

        assertThat(report.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report.baselines()).isEmpty();
        assertThat(report.evaluations()).isEmpty();
        assertThat(report.acceptance().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(report.limitations())
                .anyMatch(limitation -> limitation.contains("minimumTotalSamples"));
    }

    private LabelDryRunReport labelReport(LabelTarget target, List<LabelCandidate> candidates, long rejectedSamples) {
        long usable = candidates.stream().filter(LabelCandidate::usable).count();
        long positives = candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.positiveLabel()))
                .count();
        return new LabelDryRunReport(
                target,
                candidates.size() + rejectedSamples,
                candidates.stream().map(LabelCandidate::parkingPlaceId).distinct().count(),
                candidates.size() + rejectedSamples,
                usable,
                rejectedSamples,
                (double) usable / (double) (candidates.size() + rejectedSamples),
                positives,
                candidates.getFirst().sampleTime(),
                candidates.getLast().sampleTime(),
                Map.of(),
                candidates
        );
    }

    private LabelCandidate candidate(int minuteOffset, Long parkingPlaceId, boolean positiveLabel) {
        return new LabelCandidate(
                Instant.parse("2026-10-01T00:00:00Z").plusSeconds(minuteOffset * 60L),
                parkingPlaceId,
                true,
                positiveLabel,
                1,
                1,
                1.0,
                null
        );
    }
}
