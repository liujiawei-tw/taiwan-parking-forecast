package parking.forecast.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import parking.forecast.service.TrainingLabelDryRunService.LabelCandidate;
import parking.forecast.service.TrainingLabelDryRunService.LabelDryRunReport;
import parking.forecast.service.TrainingLabelDryRunService.LabelTarget;

@Service
public class BaselineBacktestService {

    private static final double LOG_LOSS_EPSILON = 1.0e-15;

    public BaselineBacktestReport backtest(LabelDryRunReport labelReport, BacktestCommand command) {
        Objects.requireNonNull(labelReport, "labelReport is required");
        command.validate();

        List<LabelSample> samples = usableSamples(labelReport);
        List<String> limitations = new ArrayList<>();
        if (labelReport.rejectedSamples() > 0) {
            limitations.add("Rejected label dry run samples are excluded from backtest metrics.");
        }
        if (samples.size() < command.minimumTotalSamples()) {
            limitations.add("Usable samples are below minimumTotalSamples; keep predictions as INSUFFICIENT_DATA.");
            return insufficientReport(labelReport.target(), samples, labelReport.rejectedSamples(), command, limitations);
        }

        TimeSplitSamples split = split(samples, command);
        if (split.training().size() < command.minimumTrainingSamples()
                || split.validation().size() < command.minimumEvaluationSamples()
                || split.test().size() < command.minimumEvaluationSamples()) {
            limitations.add("Time split does not have enough training, validation, or test samples; keep predictions as INSUFFICIENT_DATA.");
            return insufficientReport(labelReport.target(), samples, labelReport.rejectedSamples(), command, limitations, split);
        }

        double trainPositiveRate = positiveRate(split.training());
        boolean trainMajorityPositive = trainPositiveRate >= 0.5;
        Map<Long, Boolean> latestTrainingLabelByPlace = split.training().stream()
                .filter(sample -> sample.parkingPlaceId() != null)
                .collect(Collectors.toMap(
                        LabelSample::parkingPlaceId,
                        LabelSample::positiveLabel,
                        (earlier, later) -> later
                ));
        Boolean latestTrainingTargetLabel = split.training().getLast().positiveLabel();

        List<BaselineDefinition> definitions = List.of(
                new BaselineDefinition(
                        "TRAIN_POSITIVE_RATE",
                        "Predict the training-window positive label rate for every later sample.",
                        trainPositiveRate
                ),
                new BaselineDefinition(
                        "TRAIN_MAJORITY_CLASS",
                        "Predict the majority class observed in the training window.",
                        trainMajorityPositive ? 1.0 : 0.0
                ),
                new BaselineDefinition(
                        "LATEST_TRAINING_LABEL",
                        "Predict the latest training label for the same off-street lot when available, otherwise the latest target label.",
                        null
                )
        );

        List<BaselineEvaluation> evaluations = new ArrayList<>();
        for (BaselineDefinition definition : definitions) {
            evaluations.add(evaluate(definition, "VALIDATION", split.validation(), latestTrainingLabelByPlace, latestTrainingTargetLabel));
            evaluations.add(evaluate(definition, "TEST", split.test(), latestTrainingLabelByPlace, latestTrainingTargetLabel));
        }

        AcceptanceDecision acceptance = decideAcceptance(split, evaluations, command);
        return new BaselineBacktestReport(
                labelReport.target(),
                "READY",
                samples.size(),
                labelReport.rejectedSamples(),
                samples.getFirst().sampleTime(),
                samples.getLast().sampleTime(),
                split.toSummary(),
                definitions,
                evaluations,
                acceptance,
                List.copyOf(limitations)
        );
    }

    private List<LabelSample> usableSamples(LabelDryRunReport labelReport) {
        return labelReport.candidates().stream()
                .filter(LabelCandidate::usable)
                .filter(candidate -> candidate.sampleTime() != null)
                .filter(candidate -> candidate.positiveLabel() != null)
                .sorted(Comparator.comparing(LabelCandidate::sampleTime)
                        .thenComparing(LabelCandidate::parkingPlaceId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(candidate -> new LabelSample(candidate.sampleTime(), candidate.parkingPlaceId(), candidate.positiveLabel()))
                .toList();
    }

    private TimeSplitSamples split(List<LabelSample> samples, BacktestCommand command) {
        int trainingEnd = Math.min(
                samples.size() - 2,
                Math.max(1, (int) Math.floor(samples.size() * command.trainingRatio()))
        );
        int validationEnd = Math.max(trainingEnd + 1, (int) Math.floor(samples.size() * (command.trainingRatio() + command.validationRatio())));
        validationEnd = Math.min(validationEnd, samples.size() - 1);
        return new TimeSplitSamples(
                samples.subList(0, trainingEnd),
                samples.subList(trainingEnd, validationEnd),
                samples.subList(validationEnd, samples.size())
        );
    }

    private BaselineEvaluation evaluate(
            BaselineDefinition definition,
            String splitName,
            List<LabelSample> samples,
            Map<Long, Boolean> latestTrainingLabelByPlace,
            Boolean latestTrainingTargetLabel
    ) {
        List<ScoredLabel> scored = samples.stream()
                .map(sample -> new ScoredLabel(sample.positiveLabel(), probability(definition, sample, latestTrainingLabelByPlace, latestTrainingTargetLabel)))
                .toList();
        long positives = scored.stream().filter(ScoredLabel::label).count();
        long predictedPositive = scored.stream().filter(score -> score.probability() >= 0.5).count();
        long truePositive = scored.stream()
                .filter(score -> score.label() && score.probability() >= 0.5)
                .count();
        long falseNegative = scored.stream()
                .filter(score -> score.label() && score.probability() < 0.5)
                .count();
        long trueNegative = scored.stream()
                .filter(score -> !score.label() && score.probability() < 0.5)
                .count();
        long falsePositive = scored.stream()
                .filter(score -> !score.label() && score.probability() >= 0.5)
                .count();

        return new BaselineEvaluation(
                definition.name(),
                splitName,
                samples.size(),
                ratio(positives, samples.size()),
                brierScore(scored),
                logLoss(scored),
                ratio(truePositive + trueNegative, samples.size()),
                ratio(truePositive, truePositive + falsePositive),
                ratio(truePositive, truePositive + falseNegative),
                predictedPositive,
                truePositive,
                falsePositive,
                falseNegative
        );
    }

    private double probability(
            BaselineDefinition definition,
            LabelSample sample,
            Map<Long, Boolean> latestTrainingLabelByPlace,
            Boolean latestTrainingTargetLabel
    ) {
        if (definition.constantProbability() != null) {
            return definition.constantProbability();
        }
        Boolean samePlaceLabel = sample.parkingPlaceId() == null ? null : latestTrainingLabelByPlace.get(sample.parkingPlaceId());
        Boolean label = samePlaceLabel == null ? latestTrainingTargetLabel : samePlaceLabel;
        return Boolean.TRUE.equals(label) ? 1.0 : 0.0;
    }

    private AcceptanceDecision decideAcceptance(
            TimeSplitSamples split,
            List<BaselineEvaluation> evaluations,
            BacktestCommand command
    ) {
        List<BaselineEvaluation> testEvaluations = evaluations.stream()
                .filter(evaluation -> "TEST".equals(evaluation.splitName()))
                .toList();
        double bestTestBrier = testEvaluations.stream()
                .mapToDouble(BaselineEvaluation::brierScore)
                .min()
                .orElse(Double.NaN);
        double testPositiveRate = positiveRate(split.test());
        boolean singleClassTest = testPositiveRate == 0.0 || testPositiveRate == 1.0;
        if (singleClassTest) {
            return new AcceptanceDecision(
                    "INSUFFICIENT_DATA",
                    bestTestBrier,
                    command.maximumAcceptedBrierScore(),
                    "Test split contains only one label class; do not claim predictive quality."
            );
        }
        if (bestTestBrier > command.maximumAcceptedBrierScore()) {
            return new AcceptanceDecision(
                    "INSUFFICIENT_DATA",
                    bestTestBrier,
                    command.maximumAcceptedBrierScore(),
                    "Best baseline Brier score is above the predeclared threshold."
            );
        }
        return new AcceptanceDecision(
                "BASELINE_CANDIDATE",
                bestTestBrier,
                command.maximumAcceptedBrierScore(),
                "Baseline passes the predeclared Brier threshold, but it is still an offline candidate and not a service commitment."
        );
    }

    private BaselineBacktestReport insufficientReport(
            LabelTarget target,
            List<LabelSample> samples,
            long rejectedLabelSamples,
            BacktestCommand command,
            List<String> limitations
    ) {
        return insufficientReport(
                target,
                samples,
                rejectedLabelSamples,
                command,
                limitations,
                new TimeSplitSamples(List.of(), List.of(), List.of())
        );
    }

    private BaselineBacktestReport insufficientReport(
            LabelTarget target,
            List<LabelSample> samples,
            long rejectedLabelSamples,
            BacktestCommand command,
            List<String> limitations,
            TimeSplitSamples split
    ) {
        return new BaselineBacktestReport(
                target,
                "INSUFFICIENT_DATA",
                samples.size(),
                rejectedLabelSamples,
                samples.isEmpty() ? null : samples.getFirst().sampleTime(),
                samples.isEmpty() ? null : samples.getLast().sampleTime(),
                split.toSummary(),
                List.of(),
                List.of(),
                new AcceptanceDecision(
                        "INSUFFICIENT_DATA",
                        Double.NaN,
                        command.maximumAcceptedBrierScore(),
                        "Not enough usable time-ordered labels to run baseline backtest."
                ),
                List.copyOf(limitations)
        );
    }

    private double brierScore(List<ScoredLabel> scored) {
        return scored.stream()
                .mapToDouble(score -> {
                    double observed = score.label() ? 1.0 : 0.0;
                    double error = score.probability() - observed;
                    return error * error;
                })
                .average()
                .orElse(Double.NaN);
    }

    private double logLoss(List<ScoredLabel> scored) {
        return scored.stream()
                .mapToDouble(score -> {
                    double probability = Math.min(1.0 - LOG_LOSS_EPSILON, Math.max(LOG_LOSS_EPSILON, score.probability()));
                    return score.label() ? -Math.log(probability) : -Math.log(1.0 - probability);
                })
                .average()
                .orElse(Double.NaN);
    }

    private double positiveRate(List<LabelSample> samples) {
        long positives = samples.stream().filter(LabelSample::positiveLabel).count();
        return ratio(positives, samples.size());
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / (double) denominator;
    }

    public record BacktestCommand(
            double trainingRatio,
            double validationRatio,
            int minimumTotalSamples,
            int minimumTrainingSamples,
            int minimumEvaluationSamples,
            double maximumAcceptedBrierScore
    ) {
        public void validate() {
            if (trainingRatio <= 0.0 || validationRatio <= 0.0 || trainingRatio + validationRatio >= 1.0) {
                throw new IllegalArgumentException("trainingRatio and validationRatio must leave a positive test split");
            }
            if (minimumTotalSamples <= 0 || minimumTrainingSamples <= 0 || minimumEvaluationSamples <= 0) {
                throw new IllegalArgumentException("minimum sample thresholds must be positive");
            }
            if (minimumTotalSamples < 3
                    || minimumTotalSamples < minimumTrainingSamples + minimumEvaluationSamples * 2) {
                throw new IllegalArgumentException("minimumTotalSamples must allow training, validation, and test splits");
            }
            if (maximumAcceptedBrierScore < 0.0 || maximumAcceptedBrierScore > 1.0) {
                throw new IllegalArgumentException("maximumAcceptedBrierScore must be between 0 and 1");
            }
        }
    }

    public record BaselineBacktestReport(
            LabelTarget target,
            String status,
            long usableSamples,
            long rejectedLabelSamples,
            Instant firstSampleAt,
            Instant lastSampleAt,
            TimeSplitSummary timeSplit,
            List<BaselineDefinition> baselines,
            List<BaselineEvaluation> evaluations,
            AcceptanceDecision acceptance,
            List<String> limitations
    ) {
        public BaselineBacktestReport {
            baselines = baselines == null ? List.of() : List.copyOf(baselines);
            evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record TimeSplitSummary(
            long trainingSamples,
            long validationSamples,
            long testSamples,
            Instant trainingFrom,
            Instant trainingTo,
            Instant validationFrom,
            Instant validationTo,
            Instant testFrom,
            Instant testTo
    ) {
    }

    public record BaselineDefinition(
            String name,
            String description,
            Double constantProbability
    ) {
    }

    public record BaselineEvaluation(
            String baselineName,
            String splitName,
            long samples,
            double positiveRate,
            double brierScore,
            double logLoss,
            double accuracy,
            double precision,
            double recall,
            long predictedPositive,
            long truePositive,
            long falsePositive,
            long falseNegative
    ) {
    }

    public record AcceptanceDecision(
            String status,
            double bestTestBrierScore,
            double maximumAcceptedBrierScore,
            String message
    ) {
    }

    private record LabelSample(
            Instant sampleTime,
            Long parkingPlaceId,
            boolean positiveLabel
    ) {
    }

    private record ScoredLabel(
            boolean label,
            double probability
    ) {
    }

    private record TimeSplitSamples(
            List<LabelSample> training,
            List<LabelSample> validation,
            List<LabelSample> test
    ) {
        private TimeSplitSummary toSummary() {
            return new TimeSplitSummary(
                    training.size(),
                    validation.size(),
                    test.size(),
                    firstTime(training),
                    lastTime(training),
                    firstTime(validation),
                    lastTime(validation),
                    firstTime(test),
                    lastTime(test)
            );
        }

        private Instant firstTime(List<LabelSample> samples) {
            return samples.isEmpty() ? null : samples.getFirst().sampleTime();
        }

        private Instant lastTime(List<LabelSample> samples) {
            return samples.isEmpty() ? null : samples.getLast().sampleTime();
        }
    }
}
