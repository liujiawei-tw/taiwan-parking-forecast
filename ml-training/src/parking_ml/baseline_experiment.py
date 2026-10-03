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
    nearest_observation,
    read_observations,
    run_roadside_dry_run,
    settings_from_config,
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
        dataset_reports.append(
            DatasetLeadReport(
                dataset="roadside",
                lead_minutes=lead,
                status="INSUFFICIENT_DATA",
                rejection_summary=combined,
                metrics=[
                    ExperimentMetrics(
                        name="not_run",
                        status="INSUFFICIENT_DATA",
                        train_samples=train_stats.usable,
                        test_samples=test_stats.usable,
                        train_positive_rate=None,
                        test_positive_rate=None,
                        reason=roadside_not_run_reason(train_stats, test_stats, config),
                    )
                ],
            )
        )

    return render_baseline_report(BaselineExperimentReport(dataset_reports), input_path, config)


def roadside_not_run_reason(train_stats: LabelStats, test_stats: LabelStats, config: TrainingConfig) -> str:
    min_train_samples = int(config.baseline_experiment.get("minTrainSamples", 500))
    min_test_samples = int(config.baseline_experiment.get("minTestSamples", 100))
    if train_stats.usable < min_train_samples:
        return "roadside_insufficient_train_labels_after_relabel"
    if test_stats.usable < min_test_samples:
        return "roadside_insufficient_test_labels_after_relabel"
    return "roadside_baseline_not_implemented_after_relabel"


def build_offstreet_samples(
    offstreet_by_lot: dict,
    config: TrainingConfig,
    settings: LabelDryRunSettings,
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
                if not as_of.has_source_response:
                    continue
                if arrival.is_unknown or arrival.available_car is None or arrival.available_car < 0:
                    continue
                samples.append(
                    LabelSample(
                        split=split_name,
                        lead_minutes=lead,
                        entity_id=lot_id,
                        current_available=current_available(as_of.available_car, as_of.is_unknown),
                        current_ratio=current_ratio(as_of.availability_ratio),
                        weekday=as_of.collected_at.weekday(),
                        hour=as_of.collected_at.hour,
                        label=arrival.available_car > 0,
                    )
                )
    return samples


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
    settings = config.baseline_experiment
    iterations = int(settings.get("logisticIterations", 300))
    learning_rate = float(settings.get("logisticLearningRate", 0.05))
    l2 = float(settings.get("logisticL2", 0.001))
    weights = [0.0] * len(features(train[0]))
    for _ in range(iterations):
        gradients = [0.0] * len(weights)
        for sample in train:
            values = features(sample)
            prediction = sigmoid(dot(weights, values))
            error = prediction - (1.0 if sample.label else 0.0)
            for index, value in enumerate(values):
                gradients[index] += error * value
        for index in range(len(weights)):
            penalty = l2 * weights[index] if index else 0.0
            weights[index] -= learning_rate * ((gradients[index] / len(train)) + penalty)
    return [sigmoid(dot(weights, features(sample))) for sample in test]


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
            "- Roadside confirmed availability label 已可由 raw status 離線 re-label；本 baseline 階段仍維持 `INSUFFICIENT_DATA` 或 not-run，直到 roadside 專用 baseline／模型與門檻完成。",
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
