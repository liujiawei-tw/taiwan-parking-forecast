"""Train-only calibrated persistence and auditable offline evaluation."""

from __future__ import annotations

import hashlib
import json
import math
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

from parking_ml.baseline_experiment import (
    COVERAGE_POLICY_COMPLETE,
    COVERAGE_POLICY_LEGACY,
    LabelSample,
    RoadsideCoverageAudit,
    build_offstreet_samples,
    build_roadside_samples,
    current_state_predictions,
    fit_logistic_regression,
    positive_rate,
    predict_logistic_regression,
    has_both_classes,
)
from parking_ml.collector_reader import iter_zip_paths
from parking_ml.label_dry_run import LabelStats, read_observations, settings_from_config
from parking_ml.training_config import TrainingConfig

FEATURE_NAMES = [
    "intercept",
    "current_available_signed",
    "current_available_known",
    "current_availability_ratio",
    "as_of_hour_sin",
    "as_of_hour_cos",
    "weekday_sin",
    "weekday_cos",
]
MODEL_NAMES = (
    "train_positive_rate",
    "current_state_persistence",
    "calibrated_state_persistence_v1",
    "logistic_regression_v1",
)


@dataclass(frozen=True)
class CalibratedPersistenceModel:
    fallback_probability: float
    state_probabilities: dict[bool | None, float]
    state_counts: dict[str, int]
    alpha: float
    minimum_state_samples: int

    @classmethod
    def fit(
        cls, train: list[LabelSample], *, alpha: float = 1.0, minimum_state_samples: int = 20
    ) -> "CalibratedPersistenceModel":
        if not math.isfinite(alpha) or alpha <= 0:
            raise ValueError("laplace_alpha_must_be_finite_and_positive")
        if minimum_state_samples < 1:
            raise ValueError("minimum_state_samples_must_be_positive")
        if any(sample.split != "train" for sample in train):
            raise ValueError("calibrated_persistence_fit_accepts_train_split_only")
        positives = sum(sample.label for sample in train)
        fallback = (positives + alpha) / (len(train) + 2 * alpha)
        groups: dict[bool | None, list[LabelSample]] = defaultdict(list)
        for sample in train:
            groups[sample.current_available].append(sample)
        probabilities: dict[bool | None, float] = {}
        counts: dict[str, int] = {}
        for state in (True, False, None):
            group = groups[state]
            key = state_name(state)
            counts[key] = len(group)
            if len(group) >= minimum_state_samples:
                positives_in_group = sum(sample.label for sample in group)
                probabilities[state] = (positives_in_group + alpha) / (len(group) + 2 * alpha)
        return cls(fallback, probabilities, counts, alpha, minimum_state_samples)

    def predict(self, sample: LabelSample) -> float:
        return self.state_probabilities.get(sample.current_available, self.fallback_probability)


def state_name(state: bool | None) -> str:
    return "unknown" if state is None else ("available" if state else "unavailable")


def predict_all(
    train: list[LabelSample], holdout: list[LabelSample], config: TrainingConfig,
    calibrated: CalibratedPersistenceModel,
) -> dict[str, list[float] | None]:
    logistic = fit_logistic_regression(train, config) if has_both_classes(train) else None
    return {
        "train_positive_rate": [positive_rate(train)] * len(holdout) if train else [0.5] * len(holdout),
        "current_state_persistence": current_state_predictions(train, holdout) if train else [0.5] * len(holdout),
        "calibrated_state_persistence_v1": [calibrated.predict(sample) for sample in holdout],
        "logistic_regression_v1": [predict_logistic_regression(logistic, s) for s in holdout] if logistic else None,
    }


def metric_summary(samples: list[LabelSample], probabilities: list[float] | None) -> dict | None:
    if probabilities is None or len(probabilities) != len(samples) or not samples:
        return None
    probs = [min(max(float(p), 1e-6), 1 - 1e-6) for p in probabilities]
    labels = [sample.label for sample in samples]
    hard = [p >= 0.5 for p in probs]
    tp = sum(pred and actual for pred, actual in zip(hard, labels))
    fp = sum(pred and not actual for pred, actual in zip(hard, labels))
    tn = sum(not pred and not actual for pred, actual in zip(hard, labels))
    fn = sum(not pred and actual for pred, actual in zip(hard, labels))
    near = [i for i, s in enumerate(samples) if s.current_ratio is not None and s.current_ratio <= 0.10]
    near_pos = sum(hard[i] for i in near)
    near_fp = sum(hard[i] and not labels[i] for i in near)
    near_tn = sum(not hard[i] and not labels[i] for i in near)
    return {
        "samples": len(samples), "positive": sum(labels), "negative": len(labels) - sum(labels),
        "positive_rate": sum(labels) / len(labels),
        "brier": sum((p - float(y)) ** 2 for p, y in zip(probs, labels)) / len(labels),
        "log_loss": -sum(float(y) * math.log(p) + (1 - float(y)) * math.log(1 - p) for p, y in zip(probs, labels)) / len(labels),
        "accuracy": (tp + tn) / len(labels), "precision": tp / (tp + fp) if tp + fp else None,
        "recall": tp / (tp + fn) if tp + fn else None,
        "false_positive_rate": fp / (fp + tn) if fp + tn else None,
        "false_positives": fp, "predicted_positive": tp + fp,
        "near_full": {
            "samples": len(near), "negative": sum(not labels[i] for i in near),
            "predicted_positive": near_pos, "false_positives": near_fp,
            "precision": sum(hard[i] and labels[i] for i in near) / near_pos if near_pos else None,
            "false_positive_rate": near_fp / (near_fp + near_tn) if near_fp + near_tn else None,
            "brier": sum((probs[i] - float(labels[i])) ** 2 for i in near) / len(near) if near else None,
        },
    }


def selection_gate(train: list[LabelSample], validation: list[LabelSample], config: TrainingConfig) -> str | None:
    settings = config.baseline_experiment
    if len(train) < int(settings.get("minTrainSamples", 500)):
        return "INSUFFICIENT_TRAIN_SAMPLES"
    if not has_both_classes(train):
        return "TRAIN_SINGLE_CLASS"
    if len(validation) < int(settings.get("minValidationSamples", 100)):
        return "INSUFFICIENT_VALIDATION_SAMPLES"
    if not has_both_classes(validation):
        return "VALIDATION_SINGLE_CLASS"
    return None


def test_evaluation_gate(test: list[LabelSample], config: TrainingConfig) -> str | None:
    if len(test) < int(config.baseline_experiment.get("minTestSamples", 100)):
        return "INSUFFICIENT_TEST_SAMPLES"
    if not has_both_classes(test):
        return "TEST_SINGLE_CLASS"
    return None


def choose_by_validation(metrics: dict[str, dict | None]) -> str | None:
    priority = {name: index for index, name in enumerate(MODEL_NAMES)}
    eligible = [(m["brier"], priority[name], name) for name, m in metrics.items() if m is not None]
    return min(eligible)[2] if eligible else None


def transition_summary(samples: list[LabelSample], probabilities: list[float]) -> dict[str, dict]:
    groups: dict[str, list[int]] = {"same": [], "different": [], "unknown_as_of": []}
    for i, sample in enumerate(samples):
        if sample.current_available is None:
            groups["unknown_as_of"].append(i)
        else:
            groups["same" if sample.current_available == sample.label else "different"].append(i)
    result = {}
    for name, indexes in groups.items():
        subset = [samples[i] for i in indexes]
        values = [probabilities[i] for i in indexes]
        result[name] = {
            "samples": len(subset), "positive": sum(s.label for s in subset),
            "negative": sum(not s.label for s in subset),
            "brier": metric_summary(subset, values)["brier"] if subset else None,
        }
    known = len(groups["same"]) + len(groups["different"])
    result["effective_transition_rate"] = len(groups["different"]) / known if known else None
    return result


def daily_summary(samples: list[LabelSample], probabilities: list[float]) -> dict[str, dict]:
    grouped: dict[str, list[int]] = defaultdict(list)
    for i, sample in enumerate(samples):
        grouped[sample.as_of_date or "unknown_date"].append(i)
    result = {}
    for day, indexes in sorted(grouped.items()):
        summary = metric_summary([samples[i] for i in indexes], [probabilities[i] for i in indexes])
        result[day] = {key: summary[key] for key in ("samples", "positive_rate", "brier", "false_positive_rate")}
    return result


def calibration_summary(samples: list[LabelSample], probabilities: list[float], bins: int = 10, minimum: int = 20) -> list[dict]:
    grouped: list[list[int]] = [[] for _ in range(bins)]
    for i, probability in enumerate(probabilities):
        index = min(int(max(0.0, min(1.0, probability)) * bins), bins - 1)
        grouped[index].append(i)
    rows = []
    for index, members in enumerate(grouped):
        if not members:
            continue
        rows.append({
            "interval": f"[{index / bins:.1f}, {(index + 1) / bins:.1f}{']' if index == bins - 1 else ')'}",
            "samples": len(members),
            "mean_probability": sum(probabilities[i] for i in members) / len(members),
            "observed_rate": sum(samples[i].label for i in members) / len(members),
            "status": "OK" if len(members) >= minimum else "LOW_SAMPLE",
        })
    return rows


def _audit_rows(audit: dict[tuple[str, int], LabelStats], dataset: str, policy: str) -> list[dict]:
    rows = []
    for (split, lead), stats in sorted(audit.items()):
        rows.append({
            "dataset": dataset, "policy": policy, "split": split, "lead": lead,
            "candidates": stats.candidates, "accepted": stats.usable,
            "rejected": stats.candidates - stats.usable, "positive": stats.positive,
            "negative": stats.negative, "acceptance_rate": stats.usable / stats.candidates if stats.candidates else None,
            "reasons": dict(sorted(stats.rejected.items())),
        })
    return rows


def logistic_artifact(*, cohort: str, policy: str, lead: int, weights: list[float], train: list[LabelSample],
                      config: TrainingConfig, source_hash: str, config_hash: str) -> dict:
    return {
        "schemaVersion": "parking-logistic-experiment-v2",
        "featureVersion": "parking_features_as_of_v2",
        "modelId": f"{cohort}-lead-{lead}m-logistic-experiment-v2",
        "status": "EXPERIMENT_ONLY", "publishToOnlineSystem": False,
        "dataset": cohort, "coveragePolicyVersion": policy,
        "target": "arrival_has_confirmed_availability", "leadMinutes": lead,
        "decisionThreshold": 0.5, "featureNames": FEATURE_NAMES,
        "featureTimeBasis": "as_of_collected_at_in_Asia/Taipei",
        "weights": weights, "trainingSamples": len(train),
        "trainingPositiveRate": positive_rate(train),
        "trainingDateRange": {"start": config.split.train.start.isoformat(), "end": config.split.train.end.isoformat()},
        "validationDateRange": {"start": config.split.validation.start.isoformat(), "end": config.split.validation.end.isoformat()},
        "testDateRange": {"start": config.split.test.start.isoformat(), "end": config.split.test.end.isoformat()},
        "hyperparameters": {key: config.baseline_experiment.get(key) for key in ("logisticIterations", "logisticLearningRate", "logisticL2")},
        "sourceZipSha256": source_hash, "trainingConfigSha256": config_hash,
    }


def evaluate_validation_then_test(train: list[LabelSample], validation: list[LabelSample], test: list[LabelSample],
                                  config: TrainingConfig, calibrated: CalibratedPersistenceModel) -> dict:
    gate = selection_gate(train, validation, config)
    validation_predictions = predict_all(train, validation, config, calibrated)
    validation_metrics = {name: metric_summary(validation, validation_predictions[name]) for name in MODEL_NAMES}
    selected = choose_by_validation(validation_metrics) if gate is None else None
    test_gate = test_evaluation_gate(test, config) if selected is not None else "NOT_OPENED_BEFORE_VALIDATION_SELECTION"
    test_predictions = predict_all(train, test, config, calibrated) if selected is not None and test_gate is None else {name: None for name in MODEL_NAMES}
    test_metrics = {name: metric_summary(test, test_predictions[name]) for name in MODEL_NAMES}
    return {
        "gate": gate, "selected": selected,
        "validation_metrics": validation_metrics,
        "test_gate": test_gate, "test_metrics": test_metrics,
        "test_predictions": test_predictions,
    }


def run_calibrated_experiment(
    input_path: Path, config_path: Path, output_path: Path, artifact_directory: Path,
    config: TrainingConfig, historical_report_path: Path | None = None,
) -> str:
    if config.split.validation is None:
        raise ValueError("80/10/10 experiment requires validationStart and validationEnd")
    settings = settings_from_config(config)
    complete_policy = str(config.baseline_experiment.get("roadsideCoveragePolicyVersion", COVERAGE_POLICY_COMPLETE))
    observations = read_observations(input_path)
    admission: dict[str, dict[tuple[str, int], LabelStats]] = defaultdict(dict)
    coverage: dict[str, dict[tuple[str, int], RoadsideCoverageAudit]] = {
        "roadside_legacy": {}, "roadside_complete": {}
    }
    offstreet = build_offstreet_samples(observations.offstreet_by_lot, config, settings, strict_split_boundaries=True, admission_audit=admission["offstreet"])
    roadside_legacy = build_roadside_samples(observations.roadside_by_spot, config, settings, strict_split_boundaries=True, coverage_policy_version=COVERAGE_POLICY_LEGACY, admission_audit=admission["roadside_legacy"], coverage_audit=coverage["roadside_legacy"])
    roadside_complete = build_roadside_samples(observations.roadside_by_spot, config, settings, strict_split_boundaries=True, coverage_policy_version=complete_policy, admission_audit=admission["roadside_complete"], coverage_audit=coverage["roadside_complete"])
    cohorts = {
        "offstreet": ("OFFSTREET", offstreet),
        "roadside_legacy": (COVERAGE_POLICY_LEGACY, roadside_legacy),
        "roadside_complete": (COVERAGE_POLICY_COMPLETE, roadside_complete),
    }
    zip_paths = iter_zip_paths(input_path)
    source_hash = hash_files(zip_paths)
    config_hash = hashlib.sha256(config_path.read_bytes()).hexdigest()
    alpha = float(config.baseline_experiment.get("calibratedPersistenceAlpha", 1.0))
    minimum_state = int(config.baseline_experiment.get("calibratedPersistenceMinStateSamples", 20))
    minimum_bin = int(config.baseline_experiment.get("calibrationMinBucketSamples", 20))
    bins = int(config.baseline_experiment.get("calibrationBins", 10))
    if bins < 1:
        raise ValueError("calibration_bins_must_be_positive")
    artifact_directory.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    artifact_paths: list[str] = []

    for cohort, (policy, samples) in cohorts.items():
        for lead in settings.lead_minutes:
            lead_samples = [s for s in samples if s.lead_minutes == lead]
            train = [s for s in lead_samples if s.split == "train"]
            validation = [s for s in lead_samples if s.split == "validation"]
            test = [s for s in lead_samples if s.split == "test"]
            calibrated = CalibratedPersistenceModel.fit(train, alpha=alpha, minimum_state_samples=minimum_state)
            evaluation = evaluate_validation_then_test(train, validation, test, config, calibrated)
            gate, selected, test_gate = evaluation["gate"], evaluation["selected"], evaluation["test_gate"]
            val_metrics, test_predictions, test_metrics = evaluation["validation_metrics"], evaluation["test_predictions"], evaluation["test_metrics"]
            diagnostics = {}
            for name in MODEL_NAMES:
                values = test_predictions.get(name)
                if values is not None:
                    diagnostics[name] = {
                        "transitions": transition_summary(test, values),
                        "daily": daily_summary(test, values),
                        "calibration": calibration_summary(test, values, bins, minimum_bin),
                    }
            artifact_path = None
            if gate is None and has_both_classes(train) and len(train) >= int(config.baseline_experiment.get("minTrainSamples", 500)):
                weights = fit_logistic_regression(train, config)
                artifact_path = artifact_directory / f"{cohort}-lead-{lead}m-logistic-v2.json"
                artifact = logistic_artifact(cohort=cohort, policy=policy, lead=lead, weights=weights, train=train,
                                             config=config, source_hash=source_hash, config_hash=config_hash)
                artifact_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                artifact_paths.append(artifact_path.as_posix())
            rows.append({
                "cohort": cohort, "policy": policy, "lead": lead,
                "train": train, "validation": validation, "test": test,
                "gate": gate or ("SELECTED" if selected else "NO_ELIGIBLE_VALIDATION_MODEL"),
                "test_gate": test_gate,
                "selected": selected, "validation_metrics": val_metrics,
                "test_metrics": test_metrics, "diagnostics": diagnostics,
                "calibration_parameters": {
                    "alpha": alpha, "minimum_state_samples": minimum_state,
                    "fallback_probability": calibrated.fallback_probability,
                    "state_probabilities": {state_name(k): v for k, v in calibrated.state_probabilities.items()},
                    "state_counts": calibrated.state_counts,
                },
                "artifact_path": artifact_path,
            })

    history_digest = hashlib.sha256(historical_report_path.read_bytes()).hexdigest() if historical_report_path and historical_report_path.is_file() else None
    report = render_report(
        input_path=input_path, config=config, source_hash=source_hash, config_hash=config_hash,
        zip_count=len(zip_paths), source_rows=observations.source_rows, rows=rows,
        admissions={name: _audit_rows(audit, name, "not_applicable" if name == "offstreet" else COVERAGE_POLICY_COMPLETE if name == "roadside_complete" else COVERAGE_POLICY_LEGACY) for name, audit in admission.items()},
        coverage=coverage, alpha=alpha, minimum_state=minimum_state,
        minimum_bin=minimum_bin, bins=bins, artifact_paths=artifact_paths,
        historical_report_path=historical_report_path, historical_report_sha256=history_digest,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report, encoding="utf-8")
    return report


def render_report(*, input_path: Path, config: TrainingConfig, source_hash: str, config_hash: str, zip_count: int,
                  source_rows: Counter, rows: list[dict], admissions: dict[str, list[dict]], coverage: dict,
                  alpha: float, minimum_state: int, minimum_bin: int, bins: int, artifact_paths: list[str],
                  historical_report_path: Path | None, historical_report_sha256: str | None) -> str:
    lines = [
        "# Sprint 3 Calibrated Persistence 與評估可信度實驗", "",
        "## 範圍與資料版本", "",
        f"- 資料輸入：`{input_path}`；ZIP 數：{zip_count}；roadside 原始列 {source_rows.get('roadside', 0):,}、offstreet 原始列 {source_rows.get('offstreet', 0):,}。",
        f"- Train：{config.split.train.start} 至 {config.split.train.end}；Validation：{config.split.validation.start} 至 {config.split.validation.end}；Test：{config.split.test.start} 至 {config.split.test.end}。",
        f"- 日曆切分比例：{len(config.split.train.dates())}/{len(config.split.train.dates()) + len(config.split.validation.dates()) + len(config.split.test.dates())} train、{len(config.split.validation.dates())}/{len(config.split.train.dates()) + len(config.split.validation.dates()) + len(config.split.test.dates())} validation、{len(config.split.test.dates())}/{len(config.split.train.dates()) + len(config.split.validation.dates()) + len(config.split.test.dates())} test；有效標籤比例會因准入與跨界排除而異。",
        f"- ZIP SHA-256：`{source_hash}`；設定 SHA-256：`{config_hash}`。", "",
        "Calibrated Persistence 僅以 train 計算 dataset／lead cohort 內 True、False、Unknown 狀態正例率；Laplace alpha={:.2f}，每狀態最低樣本數={}，低於門檻回退至 train 全域平滑正例率。未知／空 train 不產生 NaN。".format(alpha, minimum_state),
        "各 cohort 內四種方法使用同一份已准入樣本與日期切分；roadside 舊政策與完整 coverage 政策分開評估，兩者分數不可直接解讀為同條件模型勝負。Validation Brier 選模；只有資料門檻與正負類別檢查通過後才計算 test 指標。",
        "2026-10-22 至 10-24 已被先前 0993b17 報告分析；本報告屬新增方法後的探索性重跑，不是獨立最終 holdout。", "",
        "## Roadside coverage 與資料准入", "",
        "嚴格政策以 train split 凍結候選圈；`roadside_complete_circle_v1` 要求 as-of、arrival、共同觀測格位皆完整，缺資料直接拒收。`roadside_partial_observation_v0993b17` 保留舊版部分觀測行為供重現。",
        "候選／接受／拒絕與原因（不代表所有使用者查詢的預測覆蓋率）：", "",
        "| Cohort | Policy | Split | Lead | 候選 | 接受 | 拒絕 | 正例 | 負例 | 接受率 | 拒絕原因 |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for cohort, items in admissions.items():
        for r in items:
            reasons = json.dumps(r["reasons"], ensure_ascii=False, sort_keys=True)
            lines.append(f"| {cohort} | `{r['policy']}` | {r['split']} | {r['lead']}m | {r['candidates']} | {r['accepted']} | {r['rejected']} | {r['positive']} | {r['negative']} | {fmt(r['acceptance_rate'])} | `{reasons}` |")
    lines += ["", "Coverage 觀測統計以已找到 as-of／arrival 配對的格位觀測為分母；complete policy 逐配對拒收不完整集合。", "", "| Policy | Split | Lead | 配對數 | 預期格位總數 | as-of 觀測 | arrival 觀測 | 共同觀測 | as-of ratio | arrival ratio | common ratio |", "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for cohort, audits in coverage.items():
        for (split, lead), audit in sorted(audits.items()):
            r = audit.summary()
            lines.append(f"| `{COVERAGE_POLICY_LEGACY if cohort == 'roadside_legacy' else COVERAGE_POLICY_COMPLETE}` | {split} | {lead}m | {r['matchedPairs']} | {r['expectedCandidateCircleSpots']} | {r['asOfObservedSpots']} | {r['arrivalObservedSpots']} | {r['commonObservedSpots']} | {fmt(r['asOfCoverageRatio'])} | {fmt(r['arrivalCoverageRatio'])} | {fmt(r['commonCoverageRatio'])} |")
    lines += ["", "## Validation 選模與 Test 指標", "", "機率分類門檻 0.5。低可用比例子集沿用 `current_ratio <= 0.10` 的實驗定義；分母為零顯示 N/A。", "", "| Cohort | Policy | Lead | Split | Model | Gate／選模 | n | 正例率 | Brier | Log loss | Accuracy | Precision | Recall | FPR | FP | 預測正例 | near-full n／負例／預測正例／FP | near-full Precision | near-full FPR | near-full Brier |", "| --- | --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |"]
    for row in rows:
        for split in ("validation", "test"):
            metrics = row[f"{split}_metrics"]
            for model in MODEL_NAMES:
                m = metrics[model]
                status = (row["test_gate"] or "TEST_EVALUATED") if split == "test" else ("可選模" if row["gate"] in {"SELECTED", "NO_ELIGIBLE_VALIDATION_MODEL"} else row["gate"])
                if split == "test" and model == row["selected"]:
                    status += "；validation 選定"
                if m is None:
                    lines.append(f"| {row['cohort']} | `{row['policy']}` | {row['lead']}m | {split} | `{model}` | {status} | — | — | — | — | — | — | — | — | — | — | — | — | — | — |")
                    continue
                n = m["near_full"]
                subset = f"{n['samples']}/{n['negative']}/{n['predicted_positive']}/{n['false_positives']}"
                lines.append(f"| {row['cohort']} | `{row['policy']}` | {row['lead']}m | {split} | `{model}` | {status} | {m['samples']} | {fmt(m['positive_rate'])} | {fmt(m['brier'])} | {fmt(m['log_loss'])} | {fmt(m['accuracy'])} | {fmt(m['precision'])} | {fmt(m['recall'])} | {fmt(m['false_positive_rate'])} | {m['false_positives']} | {m['predicted_positive']} | {subset} | {fmt(n['precision'])} | {fmt(n['false_positive_rate'])} | {fmt(n['brier'])} |")
    lines += ["", "## 狀態轉換、每日表現與 Calibration", "", f"Calibration 使用 {bins} 個等寬桶；低於 {minimum_bin} 筆標記 `LOW_SAMPLE`，不視為可靠校準證據。狀態轉換為事後診斷，未作為模型特徵。", ""]
    for row in rows:
        lines += [f"### {row['cohort']}／{row['lead']}m", ""]
        for model, diagnostic in row["diagnostics"].items():
            lines += [f"#### `{model}` test 診斷", "", "狀態轉換：", "", "| 分組 | n | 正例 | 負例 | Brier |", "| --- | ---: | ---: | ---: | ---: |"]
            transitions = diagnostic["transitions"]
            for name in ("same", "different", "unknown_as_of"):
                m = transitions[name]
                lines.append(f"| {name} | {m['samples']} | {m['positive']} | {m['negative']} | {fmt(m['brier'])} |")
            lines.append(f"有效狀態轉換比例：{fmt(transitions['effective_transition_rate'])}。")
            lines += ["", "每日：", "", "| 臺北日期 | n | 正例率 | Brier | FPR |", "| --- | ---: | ---: | ---: | ---: |"]
            for day, m in diagnostic["daily"].items():
                lines.append(f"| {day} | {m['samples']} | {fmt(m['positive_rate'])} | {fmt(m['brier'])} | {fmt(m['false_positive_rate'])} |")
            lines += ["", "Calibration：", "", "| 預測機率區間 | n | 平均預測機率 | 實際正例率 | 狀態 |", "| --- | ---: | ---: | ---: | --- |"]
            for m in diagnostic["calibration"]:
                lines.append(f"| {m['interval']} | {m['samples']} | {fmt(m['mean_probability'])} | {fmt(m['observed_rate'])} | {m['status']} |")
            lines.append("")
    lines += ["## 校準參數與產物", ""]
    for row in rows:
        p = row["calibration_parameters"]
        lines.append(f"- {row['cohort']} {row['lead']}m（`{row['policy']}`）：alpha={p['alpha']}; min state n={p['minimum_state_samples']}; train fallback={fmt(p['fallback_probability'])}; state n={p['state_counts']}；state rates={{{', '.join(f'{k}: {fmt(v)}' for k, v in p['state_probabilities'].items())}}}。")
    if artifact_paths:
        lines += ["", "Logistic JSON 產物均使用 schema `parking-logistic-experiment-v2`、feature version `parking_features_as_of_v2`，且標記 `EXPERIMENT_ONLY`／`publishToOnlineSystem=false`：", ""]
        lines.extend(f"- `{p}`" for p in artifact_paths)
    lines += ["", "## 0993b17 歷史基準與限制", ""]
    if historical_report_path:
        lines.append(f"- 原固定 Persistence／Logistic 的原始 0993b17 報告：`{historical_report_path}`；SHA-256：`{historical_report_sha256 or 'NOT_FOUND'}`。先前已在未修改的 0993b17 worktree 完整重跑，輸出與既有報告 24 筆 validation/test 指標逐列一致。")
    else:
        lines.append("- 未提供舊版歷史報告路徑；需另行與原報告核對。")
    lines += [
        "- 舊版 fixed persistence 固定使用 0.95／0.05，unknown 回退 train 正例率；新增 calibrated persistence 使用 train-only 條件平滑率。不得用已分析 test 日期調參或宣稱獨立最終驗證。",
        "- Train-only 圈仍可能重疊；test 僅三天且同區時間外推，格位標籤及來源更新時間語意仍待驗證。完整 coverage 會降低接受率；任何接受率都不是使用者預測覆蓋率。",
        "- 固定 lead collector 標籤不是 ETA-aware；S3-020 gate、更多未使用日期、來源 timestamp 語意與跨圈群聚評估仍未完成。結果不能作為上線准入。",
        "- 本報告只含彙總資料，不含逐列標籤、地點／格位 ID 或原始 ZIP；未呼叫官方 API／Google Routes。", "",
    ]
    return "\n".join(lines)


def hash_files(paths: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode("utf-8"))
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()


def fmt(value: float | int | None) -> str:
    return "N/A" if value is None else f"{value:.4f}" if isinstance(value, float) else str(value)
