"""Train an offline logistic candidate and evaluate chronological 80/10/10 splits."""

from __future__ import annotations

import hashlib
import json
import math
from collections import Counter
from datetime import date
from pathlib import Path

from parking_ml.baseline_experiment import (
    LabelSample,
    build_offstreet_samples,
    build_roadside_samples,
    current_state_predictions,
    fit_logistic_regression,
    positive_rate,
    predict_logistic_regression,
)
from parking_ml.collector_reader import iter_zip_paths
from parking_ml.label_dry_run import read_observations, settings_from_config
from parking_ml.training_config import TrainingConfig


FEATURE_NAMES = [
    "intercept",
    "current_available_signed",
    "current_available_known",
    "current_availability_ratio",
    "arrival_hour_sin",
    "arrival_hour_cos",
    "weekday_sin",
    "weekday_cos",
]
MODEL_NAMES = ("train_positive_rate", "current_state_persistence", "logistic_regression_v1")


def run_three_way_experiment(
    input_path: Path,
    config_path: Path,
    output_path: Path,
    artifact_directory: Path,
    config: TrainingConfig,
) -> str:
    if config.split.validation is None:
        raise ValueError("80/10/10 experiment requires validationStart and validationEnd")

    settings = settings_from_config(config)
    observations = read_observations(input_path)
    samples_by_dataset = {
        "offstreet": build_offstreet_samples(
            observations.offstreet_by_lot, config, settings, strict_split_boundaries=True
        ),
        "roadside": build_roadside_samples(
            observations.roadside_by_spot, config, settings, strict_split_boundaries=True
        ),
    }
    zip_paths = iter_zip_paths(input_path)
    source_hash = hash_files(zip_paths)
    config_hash = hashlib.sha256(config_path.read_bytes()).hexdigest()
    artifact_directory.mkdir(parents=True, exist_ok=True)

    experiment_rows: list[dict] = []
    artifact_paths: list[str] = []
    for dataset, samples in samples_by_dataset.items():
        for lead in settings.lead_minutes:
            lead_samples = [sample for sample in samples if sample.lead_minutes == lead]
            train = [sample for sample in lead_samples if sample.split == "train"]
            validation = [sample for sample in lead_samples if sample.split == "validation"]
            test = [sample for sample in lead_samples if sample.split == "test"]
            weights = fit_logistic_regression(train, config) if has_both_classes(train) else None
            validation_metrics = evaluate_models(train, validation, weights)
            selected_model = select_by_validation_brier(validation_metrics)
            test_metrics = evaluate_models(train, test, weights)

            artifact_path = None
            if weights is not None:
                artifact_path = artifact_directory / f"{dataset}-lead-{lead}m-logistic-v1.json"
                artifact_path.write_text(
                    json.dumps(
                        model_artifact(
                            dataset=dataset,
                            lead=lead,
                            weights=weights,
                            train=train,
                            config=config,
                            source_hash=source_hash,
                            config_hash=config_hash,
                        ),
                        ensure_ascii=False,
                        indent=2,
                    )
                    + "\n",
                    encoding="utf-8",
                )
                artifact_paths.append(artifact_path.as_posix())

            experiment_rows.append(
                {
                    "dataset": dataset,
                    "lead": lead,
                    "train": train,
                    "validation": validation,
                    "test": test,
                    "validation_metrics": validation_metrics,
                    "test_metrics": test_metrics,
                    "selected_model": selected_model,
                    "artifact_path": artifact_path,
                }
            )

    report = render_report(
        input_path=input_path,
        config=config,
        zip_paths=zip_paths,
        source_hash=source_hash,
        config_hash=config_hash,
        source_rows=observations.source_rows,
        roadside_relabel_rows=observations.roadside_offline_relabel_rows,
        roadside_state_changes=roadside_state_change_summary(observations.roadside_by_spot),
        offstreet_unknown_ratio=offstreet_unknown_ratio(observations.offstreet_by_lot),
        experiment_rows=experiment_rows,
        artifact_paths=artifact_paths,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report, encoding="utf-8")
    return report


def evaluate_models(train: list[LabelSample], holdout: list[LabelSample], weights: list[float] | None) -> dict[str, dict | None]:
    if not holdout:
        return {name: None for name in MODEL_NAMES}
    prevalence = positive_rate(train) if train else 0.5
    predictions = {
        "train_positive_rate": [prevalence] * len(holdout),
        "current_state_persistence": current_state_predictions(train, holdout) if train else [0.5] * len(holdout),
        "logistic_regression_v1": (
            [predict_logistic_regression(weights, sample) for sample in holdout]
            if weights is not None
            else None
        ),
    }
    return {
        name: metric_summary(holdout, values)
        for name, values in predictions.items()
    }


def metric_summary(samples: list[LabelSample], predictions: list[float] | None) -> dict | None:
    if predictions is None or len(predictions) != len(samples):
        return None
    labels = [sample.label for sample in samples]
    clipped = [min(max(float(value), 1e-6), 1 - 1e-6) for value in predictions]
    brier = sum((probability - float(label)) ** 2 for probability, label in zip(clipped, labels)) / len(labels)
    log_loss = -sum(
        float(label) * math.log(probability) + (1 - float(label)) * math.log(1 - probability)
        for probability, label in zip(clipped, labels)
    ) / len(labels)
    hard = [probability >= 0.5 for probability in clipped]
    true_positive = sum(predicted and actual for predicted, actual in zip(hard, labels))
    false_positive = sum(predicted and not actual for predicted, actual in zip(hard, labels))
    true_negative = sum(not predicted and not actual for predicted, actual in zip(hard, labels))
    false_negative = sum(not predicted and actual for predicted, actual in zip(hard, labels))
    near_full_indexes = [
        index
        for index, sample in enumerate(samples)
        if sample.current_ratio is not None and sample.current_ratio <= 0.10
    ]
    near_full_predicted_positive = sum(hard[index] for index in near_full_indexes)
    near_full_true_positive = sum(hard[index] and labels[index] for index in near_full_indexes)
    return {
        "samples": len(samples),
        "positive_rate": sum(labels) / len(labels),
        "brier": brier,
        "log_loss": log_loss,
        "accuracy": (true_positive + true_negative) / len(labels),
        "precision": true_positive / (true_positive + false_positive) if true_positive + false_positive else None,
        "recall": true_positive / (true_positive + false_negative) if true_positive + false_negative else None,
        "false_positive_rate": false_positive / (false_positive + true_negative)
        if false_positive + true_negative
        else None,
        "false_positives": false_positive,
        "near_full_samples": len(near_full_indexes),
        "near_full_precision": (
            near_full_true_positive / near_full_predicted_positive
            if near_full_predicted_positive
            else None
        ),
    }


def select_by_validation_brier(metrics: dict[str, dict | None]) -> str | None:
    available = [
        (values["brier"], priority, name)
        for priority, name in enumerate(("current_state_persistence", "train_positive_rate", "logistic_regression_v1"))
        if (values := metrics.get(name)) is not None
    ]
    return min(available)[2] if available else None


def has_both_classes(samples: list[LabelSample]) -> bool:
    return {sample.label for sample in samples} == {False, True}


def model_artifact(
    *,
    dataset: str,
    lead: int,
    weights: list[float],
    train: list[LabelSample],
    config: TrainingConfig,
    source_hash: str,
    config_hash: str,
) -> dict:
    return {
        "schemaVersion": "parking-logistic-experiment-v1",
        "modelId": f"{dataset}-lead-{lead}m-logistic-experiment-v1",
        "status": "EXPERIMENT_ONLY",
        "publishToOnlineSystem": False,
        "dataset": dataset,
        "target": "arrival_has_confirmed_availability",
        "leadMinutes": lead,
        "decisionThreshold": 0.5,
        "featureNames": FEATURE_NAMES,
        "weights": weights,
        "trainingSamples": len(train),
        "trainingPositiveRate": positive_rate(train) if train else None,
        "trainingDateRange": {
            "start": config.split.train.start.isoformat(),
            "end": config.split.train.end.isoformat(),
        },
        "validationDateRange": {
            "start": config.split.validation.start.isoformat(),
            "end": config.split.validation.end.isoformat(),
        },
        "testDateRange": {
            "start": config.split.test.start.isoformat(),
            "end": config.split.test.end.isoformat(),
        },
        "featureTimeZone": "Asia/Taipei",
        "hyperparameters": {
            "iterations": int(config.baseline_experiment.get("logisticIterations", 300)),
            "learningRate": float(config.baseline_experiment.get("logisticLearningRate", 0.05)),
            "l2": float(config.baseline_experiment.get("logisticL2", 0.001)),
        },
        "sourceZipSha256": source_hash,
        "trainingConfigSha256": config_hash,
    }


def hash_files(paths: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode("utf-8"))
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()


def roadside_state_change_summary(roadside_by_spot: dict) -> dict[str, int]:
    comparisons = 0
    changes = 0
    for rows in roadside_by_spot.values():
        statuses = [row.parking_status_raw for row in rows]
        comparisons += max(0, len(statuses) - 1)
        changes += sum(left != right for left, right in zip(statuses, statuses[1:]))
    return {"comparisons": comparisons, "changes": changes}


def offstreet_unknown_ratio(offstreet_by_lot: dict) -> float | None:
    rows = [row for values in offstreet_by_lot.values() for row in values]
    if not rows:
        return None
    return sum(row.is_unknown or row.available_car is None or row.available_car < 0 for row in rows) / len(rows)


def render_report(
    *,
    input_path: Path,
    config: TrainingConfig,
    zip_paths: list[Path],
    source_hash: str,
    config_hash: str,
    source_rows: Counter[str],
    roadside_relabel_rows: int,
    roadside_state_changes: dict[str, int],
    offstreet_unknown_ratio: float | None,
    experiment_rows: list[dict],
    artifact_paths: list[str],
) -> str:
    validation = config.split.validation
    assert validation is not None
    total_days = sum(len(part.dates()) for part in (config.split.train, validation, config.split.test))
    day_counts = {
        "train": len(config.split.train.dates()),
        "validation": len(validation.dates()),
        "test": len(config.split.test.dates()),
    }
    lines = [
        "# Sprint 3 離線 ML 訓練與 80/10/10 時間切分測試",
        "",
        "## 範圍與切分",
        "",
        f"- 輸入：`{input_path}`；daily ZIP 數：{len(zip_paths)}。",
        f"- ZIP 資料摘要：roadside {source_rows.get('roadside', 0):,} 列；offstreet {source_rows.get('offstreet', 0):,} 列。",
        f"- Train：{config.split.train.start.isoformat()} 至 {config.split.train.end.isoformat()}（{day_counts['train']}／{total_days} 日，{day_counts['train'] / total_days:.1%}）。",
        f"- Validation：{validation.start.isoformat()} 至 {validation.end.isoformat()}（{day_counts['validation']}／{total_days} 日，{day_counts['validation'] / total_days:.1%}）。",
        f"- Test：{config.split.test.start.isoformat()} 至 {config.split.test.end.isoformat()}（{day_counts['test']}／{total_days} 日，{day_counts['test'] / total_days:.1%}）。",
        "- 採日期順序切分；同一個 30／60 分鐘樣本的 as-of 與 arrival 必須落在同一 split。邊界跨越樣本會剔除，不使用 row-random split。",
        "- 模型在 train 擬合；validation 只選出 Brier 最低的比較方法；test 保留作最後評估。模型參數不使用 validation 或 test 重訓。",
        "- 這是固定 30／60 分鐘 lead 的 collector 離線實驗，還不是 ETA-aware 產品模型；未發布線上、未連接使用者查詢或官方刷新。",
        "",
        "## 資料品質檢查",
        "",
        f"- Roadside 離線重標記列數：{roadside_relabel_rows:,}；採用 `ntpc_roadside_confirmed_available_status_2_else_false_v1`，false 表示未確認有一般可停空位。",
        f"- Roadside raw `parking_status` 相鄰比較：{roadside_state_changes['comparisons']:,} 次，狀態變化 {roadside_state_changes['changes']:,} 次。",
        f"- Offstreet unknown／負值／空值觀測比例：{fmt(offstreet_unknown_ratio)}；label pairing 會拒絕未知 arrival。",
        f"- ZIP SHA-256：`{source_hash}`；設定 SHA-256：`{config_hash}`。",
        "",
        "## 各 split 有效樣本數",
        "",
        "| Dataset | Lead | Train n（占比） | Validation n（占比） | Test n（占比） | Train 正例率 | Validation 正例率 | Test 正例率 | Logistic 狀態 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for result in experiment_rows:
        counts = {key: len(result[key]) for key in ("train", "validation", "test")}
        total = sum(counts.values())
        rates = {
            key: positive_rate(result[key]) if result[key] else None
            for key in ("train", "validation", "test")
        }
        status = "已訓練" if result["artifact_path"] else "未訓練：train 缺少任一類別"
        lines.append(
            f"| {result['dataset']} | {result['lead']}m | {counts['train']:,}（{counts['train'] / total:.1%}） | "
            f"{counts['validation']:,}（{counts['validation'] / total:.1%}） | {counts['test']:,}（{counts['test'] / total:.1%}） | "
            f"{fmt(rates['train'])} | {fmt(rates['validation'])} | {fmt(rates['test'])} | {status} |"
            if total
            else f"| {result['dataset']} | {result['lead']}m | 0 | 0 | 0 | — | — | — | 未訓練：無有效樣本 |"
        )

    lines.extend(
        [
            "",
            "## Validation 與 test 指標",
            "",
            "機率門檻為 0.5；FPR 是 false positive／所有實際負例。低可用比例子集為 as-of 可用比例不高於 10%；路外對應 near-full。",
            "",
            "| Dataset | Lead | Split | 方法 | 樣本數 | 正例率 | Brier | Log loss | Accuracy | Precision | Recall | FPR | 低可用比例 n | 低可用比例 precision |",
            "| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for result in experiment_rows:
        selected = result["selected_model"]
        for split_name in ("validation", "test"):
            metric_map = result[f"{split_name}_metrics"]
            for model_name in MODEL_NAMES:
                metric = metric_map.get(model_name)
                if metric is None:
                    lines.append(
                        f"| {result['dataset']} | {result['lead']}m | {split_name} | `{model_name}` | — | — | — | — | — | — | — | — | — | — |"
                    )
                    continue
                label = f"`{model_name}`" + ("（validation 選定）" if split_name == "test" and model_name == selected else "")
                lines.append(
                    f"| {result['dataset']} | {result['lead']}m | {split_name} | {label} | {metric['samples']:,} | "
                    f"{fmt(metric['positive_rate'])} | {fmt(metric['brier'])} | {fmt(metric['log_loss'])} | {fmt(metric['accuracy'])} | "
                    f"{fmt(metric['precision'])} | {fmt(metric['recall'])} | {fmt(metric['false_positive_rate'])} | "
                    f"{metric['near_full_samples']:,} | {fmt(metric['near_full_precision'])} |"
                )

    lines.extend(["", "## 選模與本機候選產物", ""])
    for result in experiment_rows:
        lines.append(
            f"- {result['dataset']} {result['lead']}m：validation 選擇 `{result['selected_model'] or '無可比較方法'}`。"
        )
    if artifact_paths:
        lines.append("- 已訓練 Logistic Regression 權重，僅供離線重現：")
        lines.extend(f"  - `{path}`" for path in artifact_paths)
        lines.append("- 模型 JSON 標記 `EXPERIMENT_ONLY` 與 `publishToOnlineSystem=false`；不代表發布資格。")
    else:
        lines.append("- Training split 未形成同時含正負例的資料集；沒有寫出 Logistic Regression 權重檔。")
    lines.extend(
        [
            "",
            "## 結果解讀與限制",
            "",
            "- 以 test Brier 與 validation 選定方法相比，才是這次留出測試的主要比較；高 accuracy 可能來自類別比例，需一併看 Brier、FPR 與 near-full precision。",
            "- `current_state_persistence` 使用固定 0.95／0.05 分數，不是經校準的機率；validation 的最低 Brier 只用來選擇本次比較方法，尚未代表 S3-020 模型准入通過。",
            "- Roadside circle 是以 train 期間可知的座標建立，test 仍是相同區域的時間外推；此設計不測新地點泛化。圈之間重疊也會讓樣本不獨立。",
            "- Collector 收集時間約每 30 分鐘，來源 response time 不等同逐筆官方觀測時間；此資料不足以支持精確短 ETA 或即時承諾。",
            "- Test 只有三個日期；可用來檢查實驗流程與近期時間外推，不足以代表季節、節日、其他縣市或正式服務準確率。",
            "- 本報告只列聚合指標，不輸出逐列標籤、停車場／格位 ID、地址或原始 ZIP 內容。",
            "",
        ]
    )
    return "\n".join(lines)


def fmt(value: float | None) -> str:
    return "—" if value is None else f"{value:.4f}"
