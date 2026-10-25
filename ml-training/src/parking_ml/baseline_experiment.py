"""Baseline and first offline experiment reporting for parking labels."""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path

from parking_ml.label_dry_run import (
    LabelDryRunSettings,
    LabelStats,
    is_provisional,
    nearest_observation,
    nearest_timestamp,
    read_observations,
    run_roadside_dry_run,
    roadside_timestamp_index,
    select_candidate_centers,
    settings_from_config,
    spots_within_radius,
)
from parking_ml.training_config import TrainingConfig


@dataclass(frozen=True)
class LabelSample:
    split: str
    lead_minutes: int
    entity_id: str
    current_available: bool | None
    current_ratio: float | None
    weekday: int
    hour: int
    label: bool


@dataclass(frozen=True)
class ExperimentMetrics:
    name: str
    status: str
    train_samples: int
    test_samples: int
    train_positive_rate: float | None
    test_positive_rate: float | None
    brier_score: float | None = None
    log_loss: float | None = None
    accuracy: float | None = None
    precision: float | None = None
    recall: float | None = None
    coverage: float | None = None
    reason: str | None = None


@dataclass
class DatasetLeadReport:
    dataset: str
    lead_minutes: int
    status: str
    rejection_summary: LabelStats | None = None
    metrics: list[ExperimentMetrics] = field(default_factory=list)


@dataclass
class BaselineExperimentReport:
    dataset_reports: list[DatasetLeadReport]


def generate_baseline_experiment_report(input_path: Path, config: TrainingConfig) -> str:
    settings = settings_from_config(config)
    observations = read_observations(input_path)
    offstreet_samples = build_offstreet_samples(observations.offstreet_by_lot, config, settings)
    roadside_samples = build_roadside_samples(observations.roadside_by_spot, config, settings)
    roadside_stats, _ = run_roadside_dry_run(observations.roadside_by_spot, config.split, settings)

    dataset_reports: list[DatasetLeadReport] = []
    for lead in settings.lead_minutes:
        lead_samples = [sample for sample in offstreet_samples if sample.lead_minutes == lead]
        dataset_reports.append(evaluate_offstreet(lead, lead_samples, config))

    for lead in settings.lead_minutes:
        train_stats = roadside_stats[("train", lead)]
        test_stats = roadside_stats[("test", lead)]
        combined = LabelStats(
            candidates=train_stats.candidates + test_stats.candidates,
            usable=train_stats.usable + test_stats.usable,
            positive=train_stats.positive + test_stats.positive,
            negative=train_stats.negative + test_stats.negative,
        )
        combined.rejected.update(train_stats.rejected)
        combined.rejected.update(test_stats.rejected)
        combined.entity_ids.update(train_stats.entity_ids)
        combined.entity_ids.update(test_stats.entity_ids)
        lead_samples = [sample for sample in roadside_samples if sample.lead_minutes == lead]
        roadside_report = evaluate_roadside(lead, lead_samples, config)
        roadside_report.rejection_summary = combined
        dataset_reports.append(roadside_report)

    return render_baseline_report(BaselineExperimentReport(dataset_reports), input_path, config)


def evaluate_roadside(lead: int, samples: list[LabelSample], config: TrainingConfig) -> DatasetLeadReport:
    train = [sample for sample in samples if sample.split == "train"]
    test = [sample for sample in samples if sample.split == "test"]
    reason = roadside_not_run_reason(train, test, config)
    if reason:
        return DatasetLeadReport(
            dataset="roadside",
            lead_minutes=lead,
            status="INSUFFICIENT_DATA",
            metrics=[
                ExperimentMetrics(
                    name="not_run",
                    status="INSUFFICIENT_DATA",
                    train_samples=len(train),
                    test_samples=len(test),
                    train_positive_rate=positive_rate_or_none(train),
                    test_positive_rate=positive_rate_or_none(test),
                    reason=reason,
                )
            ],
        )

    metrics = [
        evaluate_predictions(
            "train_positive_rate",
            train,
            test,
            [positive_rate(train)] * len(test),
        ),
        evaluate_predictions(
            "entity_positive_rate",
            train,
            test,
            entity_positive_rate_predictions(train, test),
        ),
        evaluate_predictions(
            "current_state_persistence",
            train,
            test,
            current_state_predictions(train, test),
        ),
        evaluate_predictions(
            "logistic_regression_v1",
            train,
            test,
            logistic_regression_predictions(train, test, config),
        ),
    ]
    status = "EXPERIMENT_ONLY"
    if any(metric.status == "INSUFFICIENT_DATA" for metric in metrics):
        status = "PARTIAL_EXPERIMENT"
    return DatasetLeadReport(dataset="roadside", lead_minutes=lead, status=status, metrics=metrics)


def roadside_not_run_reason(train: list[LabelSample], test: list[LabelSample], config: TrainingConfig) -> str | None:
    min_train_samples = int(config.baseline_experiment.get("minTrainSamples", 500))
    min_test_samples = int(config.baseline_experiment.get("minTestSamples", 100))
    if len(train) < min_train_samples:
        return "roadside_insufficient_train_labels_after_relabel"
    if len(test) < min_test_samples:
        return "roadside_insufficient_test_labels_after_relabel"
    if not has_both_classes(train) or not has_both_classes(test):
        return "roadside_single_class_after_relabel"
    return None


def has_both_classes(samples: list[LabelSample]) -> bool:
    return {sample.label for sample in samples} == {False, True}


def build_offstreet_samples(
    offstreet_by_lot: dict,
    config: TrainingConfig,
    settings: LabelDryRunSettings,
    *,
    strict_split_boundaries: bool = False,
) -> list[LabelSample]:
    samples: list[LabelSample] = []
    for lot_id, rows in offstreet_by_lot.items():
        timestamps = [row.collected_at for row in rows]
        for as_of in rows:
            split_name = config.split.split_for(as_of.file_date)
            if split_name is None:
                continue
            for lead in settings.lead_minutes:
                arrival = nearest_observation(rows, timestamps, as_of.collected_at + timedelta(minutes=lead), settings.tolerance)
                if arrival is None:
                    continue
                if arrival.collected_at <= as_of.collected_at:
                    continue
                if strict_split_boundaries and config.split.split_for(arrival.file_date) != split_name:
                    continue
                if not as_of.has_source_response:
                    continue
                if arrival.is_unknown or arrival.available_car is None or arrival.available_car < 0:
                    continue
                feature_time = as_of.collected_at + timedelta(hours=8) if strict_split_boundaries else as_of.collected_at
                samples.append(
                    LabelSample(
                        split=split_name,
                        lead_minutes=lead,
                        entity_id=lot_id,
                        current_available=current_available(as_of.available_car, as_of.is_unknown),
                        current_ratio=current_ratio(as_of.availability_ratio),
                        weekday=feature_time.weekday(),
                        hour=feature_time.hour,
                        label=arrival.available_car > 0,
                    )
                )
    return samples


def build_roadside_samples(
    roadside_by_spot: dict,
    config: TrainingConfig,
    settings: LabelDryRunSettings,
    *,
    strict_split_boundaries: bool = False,
) -> list[LabelSample]:
    samples: list[LabelSample] = []
    if strict_split_boundaries:
        latest_by_spot = {
            spot_id: eligible[-1]
            for spot_id, rows in roadside_by_spot.items()
            if (eligible := [row for row in rows if row.file_date <= config.split.train.end])
        }
    else:
        latest_by_spot = {spot_id: rows[-1] for spot_id, rows in roadside_by_spot.items() if rows}
    centers = select_candidate_centers(latest_by_spot, settings.max_roadside_candidate_centers)
    circle_spots = [
        spots_within_radius(center, latest_by_spot, settings.roadside_radius_meters)
        for center in centers
    ]
    by_timestamp, timestamp_dates = roadside_timestamp_index(roadside_by_spot)
    timestamps = sorted(by_timestamp)

    for center_index, spot_ids in enumerate(circle_spots):
        if not spot_ids:
            continue
        entity_id = f"circle-{center_index + 1:03d}"
        for as_of in timestamps:
            split_name = config.split.split_for(timestamp_dates[as_of])
            if split_name is None:
                continue
            as_of_rows = [
                by_timestamp[as_of][spot_id]
                for spot_id in spot_ids
                if spot_id in by_timestamp[as_of]
            ]
            if not reliable_roadside_rows(as_of_rows, settings):
                continue
            as_of_labels = [bool(row.is_available) for row in as_of_rows]
            for lead in settings.lead_minutes:
                arrival_time = nearest_timestamp(timestamps, as_of + timedelta(minutes=lead), settings.tolerance)
                if arrival_time is None or arrival_time <= as_of:
                    continue
                if strict_split_boundaries and config.split.split_for(timestamp_dates.get(arrival_time)) != split_name:
                    continue
                arrival_rows = [
                    by_timestamp[arrival_time][spot_id]
                    for spot_id in spot_ids
                    if spot_id in by_timestamp[arrival_time]
                ]
                if not reliable_roadside_rows(arrival_rows, settings):
                    continue
                arrival_labels = [bool(row.is_available) for row in arrival_rows]
                available_count = sum(1 for label in as_of_labels if label)
                feature_time = as_of + timedelta(hours=8) if strict_split_boundaries else as_of
                samples.append(
                    LabelSample(
                        split=split_name,
                        lead_minutes=lead,
                        entity_id=entity_id,
                        current_available=available_count > 0,
                        current_ratio=available_count / len(as_of_labels),
                        weekday=feature_time.weekday(),
                        hour=feature_time.hour,
                        label=any(arrival_labels),
                    )
                )
    return samples


def reliable_roadside_rows(rows: list, settings: LabelDryRunSettings) -> bool:
    if not rows:
        return False
    if any(not row.has_source_response for row in rows):
        return False
    if not settings.allow_provisional_roadside_labels and any(is_provisional(row) for row in rows):
        return False
    return not any(row.is_available is None for row in rows)


def current_available(available_car: int | None, is_unknown: bool) -> bool | None:
    if is_unknown or available_car is None or available_car < 0:
        return None
    return available_car > 0


def current_ratio(availability_ratio: float | None) -> float | None:
    if availability_ratio is None:
        return None
    return min(max(float(availability_ratio), 0.0), 1.0)


def evaluate_offstreet(lead: int, samples: list[LabelSample], config: TrainingConfig) -> DatasetLeadReport:
    train = [sample for sample in samples if sample.split == "train"]
    test = [sample for sample in samples if sample.split == "test"]
    if not train or not test:
        return DatasetLeadReport(
            dataset="offstreet",
            lead_minutes=lead,
            status="INSUFFICIENT_DATA",
            metrics=[insufficient_metric("not_enough_train_or_test_samples", train, test)],
        )

    metrics = [
        evaluate_predictions(
            "train_positive_rate",
            train,
            test,
            [positive_rate(train)] * len(test),
        ),
        evaluate_predictions(
            "entity_positive_rate",
            train,
            test,
            entity_positive_rate_predictions(train, test),
        ),
        evaluate_predictions(
            "current_state_persistence",
            train,
            test,
            current_state_predictions(train, test),
        ),
        evaluate_predictions(
            "logistic_regression_v1",
            train,
            test,
            logistic_regression_predictions(train, test, config),
        ),
    ]
    status = "EXPERIMENT_ONLY"
    if any(metric.status == "INSUFFICIENT_DATA" for metric in metrics):
        status = "PARTIAL_EXPERIMENT"
    return DatasetLeadReport(dataset="offstreet", lead_minutes=lead, status=status, metrics=metrics)


def insufficient_metric(reason: str, train: list[LabelSample], test: list[LabelSample]) -> ExperimentMetrics:
    return ExperimentMetrics(
        name="not_run",
        status="INSUFFICIENT_DATA",
        train_samples=len(train),
        test_samples=len(test),
        train_positive_rate=positive_rate_or_none(train),
        test_positive_rate=positive_rate_or_none(test),
        reason=reason,
    )


def positive_rate(samples: list[LabelSample]) -> float:
    if not samples:
        return 0.5
    return sum(1 for sample in samples if sample.label) / len(samples)


def positive_rate_or_none(samples: list[LabelSample]) -> float | None:
    if not samples:
        return None
    return positive_rate(samples)


def entity_positive_rate_predictions(train: list[LabelSample], test: list[LabelSample]) -> list[float]:
    grouped: dict[str, list[LabelSample]] = defaultdict(list)
    fallback = positive_rate(train)
    for sample in train:
        grouped[sample.entity_id].append(sample)
    rates = {entity_id: positive_rate(entity_samples) for entity_id, entity_samples in grouped.items()}
    return [rates.get(sample.entity_id, fallback) for sample in test]


def current_state_predictions(train: list[LabelSample], test: list[LabelSample]) -> list[float]:
    fallback = positive_rate(train)
    predictions = []
    for sample in test:
        if sample.current_available is None:
            predictions.append(fallback)
        elif sample.current_available:
            predictions.append(0.95)
        else:
            predictions.append(0.05)
    return predictions


def logistic_regression_predictions(train: list[LabelSample], test: list[LabelSample], config: TrainingConfig) -> list[float]:
    weights = fit_logistic_regression(train, config)
    return [predict_logistic_regression(weights, sample) for sample in test]


def fit_logistic_regression(train: list[LabelSample], config: TrainingConfig) -> list[float]:
    if not train:
        raise ValueError("Logistic regression requires training samples")
    settings = config.baseline_experiment
    iterations = int(settings.get("logisticIterations", 300))
    learning_rate = float(settings.get("logisticLearningRate", 0.05))
    l2 = float(settings.get("logisticL2", 0.001))
    weights = [0.0] * len(features(train[0]))
    feature_rows = [features(sample) for sample in train]
    for _ in range(iterations):
        gradients = [0.0] * len(weights)
        for sample, values in zip(train, feature_rows):
            prediction = sigmoid(dot(weights, values))
            error = prediction - (1.0 if sample.label else 0.0)
            for index, value in enumerate(values):
                gradients[index] += error * value
        for index in range(len(weights)):
            penalty = l2 * weights[index] if index else 0.0
            weights[index] -= learning_rate * ((gradients[index] / len(train)) + penalty)
    return weights


def predict_logistic_regression(weights: list[float], sample: LabelSample) -> float:
    return sigmoid(dot(weights, features(sample)))


def features(sample: LabelSample) -> list[float]:
    current = 0.0 if sample.current_available is None else (1.0 if sample.current_available else -1.0)
    has_current = 0.0 if sample.current_available is None else 1.0
    ratio = sample.current_ratio if sample.current_ratio is not None else 0.0
    hour_angle = 2 * math.pi * sample.hour / 24
    weekday_angle = 2 * math.pi * sample.weekday / 7
    return [
        1.0,
        current,
        has_current,
        ratio,
        math.sin(hour_angle),
        math.cos(hour_angle),
        math.sin(weekday_angle),
        math.cos(weekday_angle),
    ]


def evaluate_predictions(
    name: str,
    train: list[LabelSample],
    test: list[LabelSample],
    predictions: list[float],
) -> ExperimentMetrics:
    if not test or len(predictions) != len(test):
        return insufficient_metric("missing_predictions", train, test)
    labels = [1.0 if sample.label else 0.0 for sample in test]
    clipped = [min(max(value, 1e-6), 1 - 1e-6) for value in predictions]
    brier = sum((prediction - label) ** 2 for prediction, label in zip(clipped, labels)) / len(labels)
    log_loss = -sum(
        label * math.log(prediction) + (1 - label) * math.log(1 - prediction)
        for prediction, label in zip(clipped, labels)
    ) / len(labels)
    hard_predictions = [prediction >= 0.5 for prediction in clipped]
    positives = [label == 1.0 for label in labels]
    tp = sum(1 for predicted, actual in zip(hard_predictions, positives) if predicted and actual)
    fp = sum(1 for predicted, actual in zip(hard_predictions, positives) if predicted and not actual)
    tn = sum(1 for predicted, actual in zip(hard_predictions, positives) if not predicted and not actual)
    fn = sum(1 for predicted, actual in zip(hard_predictions, positives) if not predicted and actual)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return ExperimentMetrics(
        name=name,
        status="EVALUATED",
        train_samples=len(train),
        test_samples=len(test),
        train_positive_rate=positive_rate_or_none(train),
        test_positive_rate=positive_rate_or_none(test),
        brier_score=brier,
        log_loss=log_loss,
        accuracy=(tp + tn) / len(labels),
        precision=precision,
        recall=recall,
        coverage=1.0,
    )


def dot(weights: list[float], values: list[float]) -> float:
    return sum(weight * value for weight, value in zip(weights, values))


def sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1 / (1 + z)
    z = math.exp(value)
    return z / (1 + z)


def render_baseline_report(report: BaselineExperimentReport, input_path: Path, config: TrainingConfig) -> str:
    lines = [
        "# S3-008 baseline 與第一版模型實驗報告",
        "",
        f"- 輸入路徑：`{input_path}`",
        "- 原始 CSV／ZIP、row-level labels 與模型檔：未複製、未輸出、未寫入 Git。",
        "- 本報告只做離線回測摘要；不發布模型、不接線上 API、不產生正式機率。",
        f"- Train：{config.split.train.start.isoformat()}..{config.split.train.end.isoformat()}",
        f"- Test：{config.split.test.start.isoformat()}..{config.split.test.end.isoformat()}",
        "",
        "## Dataset Status",
        "",
        "| Dataset | Lead | Status | Train labels | Test labels | Reason |",
        "| --- | ---: | --- | ---: | ---: | --- |",
    ]
    for dataset_report in report.dataset_reports:
        first_metric = dataset_report.metrics[0]
        lines.append(
            "| "
            f"{dataset_report.dataset} | {dataset_report.lead_minutes} | `{dataset_report.status}` | "
            f"{first_metric.train_samples} | {first_metric.test_samples} | "
            f"{first_metric.reason or 'baseline_experiment_only'} |"
        )

    lines.extend(
        [
            "",
            "## Metrics",
            "",
            "| Dataset | Lead | Experiment | Status | Train positive | Test positive | Brier | Log loss | Accuracy | Precision | Recall | Coverage |",
            "| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for dataset_report in report.dataset_reports:
        for metric in dataset_report.metrics:
            lines.append(metrics_row(dataset_report.dataset, dataset_report.lead_minutes, metric))

    lines.extend(
        [
            "",
            "## Roadside Rejection Summary",
            "",
            "| Lead | Reason | Count |",
            "| ---: | --- | ---: |",
        ]
    )
    for dataset_report in report.dataset_reports:
        if dataset_report.dataset != "roadside" or dataset_report.rejection_summary is None:
            continue
        if not dataset_report.rejection_summary.rejected:
            lines.append(f"| {dataset_report.lead_minutes} | none | 0 |")
        for reason, count in dataset_report.rejection_summary.rejected.most_common():
            lines.append(f"| {dataset_report.lead_minutes} | `{reason}` | {count} |")

    lines.extend(
        [
            "",
            "## 判定",
            "",
            "- Offstreet baseline 與簡單 logistic regression 已可用時間切分回測；結果仍只是實驗，不代表可上線服務承諾。",
            "- Roadside baseline 已使用設定檔半徑的候選目的地圓與 confirmed availability label 進行離線回測；結果仍只是實驗，不代表可上線服務承諾。",
            "- Roadside 目前樣本天數短，且正例比例會隨查詢半徑大幅改變；必須再做更多日期、更多區域與門檻審查，才能進入模型版本化。",
            "- 後續若要進 #65，必須先定義模型版本、schema、門檻、回滾與正式模型產物保存規則。",
        ]
    )
    return "\n".join(lines) + "\n"


def metrics_row(dataset: str, lead: int, metric: ExperimentMetrics) -> str:
    return (
        f"| {dataset} | {lead} | `{metric.name}` | `{metric.status}` | "
        f"{format_optional(metric.train_positive_rate)} | {format_optional(metric.test_positive_rate)} | "
        f"{format_optional(metric.brier_score)} | {format_optional(metric.log_loss)} | "
        f"{format_optional(metric.accuracy)} | {format_optional(metric.precision)} | "
        f"{format_optional(metric.recall)} | {format_optional(metric.coverage)} |"
    )


def format_optional(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value:.4f}"
