"""Offline two-phase shadow evaluation for off-street car availability."""

from __future__ import annotations

import hashlib
import json
import math
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from parking_ml.baseline_experiment import (
    LabelSample, current_available, current_ratio, current_state_predictions,
    predict_logistic_regression, taipei_time,
)
from parking_ml.calibrated_experiment import CalibratedPersistenceModel, FEATURE_NAMES
from parking_ml.collector_reader import iter_collector_rows, iter_zip_paths
from parking_ml.label_dry_run import (
    OffstreetObservation, has_explicit_timezone, parse_datetime, parse_offstreet,
    strict_time_rejection_reason, taipei_date, value,
)
from parking_ml.training_config import load_training_config

SCHEMA_VERSION = "parking-offstreet-shadow-predictions-v1"
REPORT_SCHEMA_VERSION = "parking-offstreet-shadow-report-v1"
SOURCE_TYPE = "collector_zip_offline_proxy"
LEADS = (30, 60)
MODEL_NAMES = ("current_state_persistence", "calibrated_state_persistence_v1", "logistic_regression_v1")
DEFAULT_ARRIVAL_TOLERANCE_MINUTES = 15
DEFAULT_SOURCE_TIME_TOLERANCE_MINUTES = 10
DEFAULT_MAX_PREDICTIONS_PER_LEAD = 50
EXPLORATORY_MINIMUM_SAMPLES = 50
EXPLORATORY_MINIMUM_DISTINCT_DATES = 5


@dataclass(frozen=True)
class ShadowObservation:
    observation: OffstreetObservation
    source_response_at: datetime | None
    source_response_text: str
    source_timestamp_explicit: bool

    @property
    def fingerprint(self) -> str:
        obs = self.observation
        body = {
            "lotId": obs.lot_id, "collectedAt": iso_utc(obs.collected_at),
            "fileDate": obs.file_date.isoformat(), "availableCar": obs.available_car,
            "availabilityRatio": obs.availability_ratio, "isUnknown": obs.is_unknown,
            "sourceResponseAt": self.source_response_text,
            "collectedAtTimezoneExplicit": obs.collected_at_timezone_explicit,
            "fileDateExplicit": obs.file_date_explicit,
            "sourceTimestampExplicit": self.source_timestamp_explicit,
        }
        return hashlib.sha256(canonical_json(body).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Dataset:
    by_lot: dict[str, list[ShadowObservation]]
    source_rows: int
    parsed_rows: int
    skipped_rows: int
    zip_count: int
    zip_sha256: str


def generate_predictions(
    input_path: Path,
    training_config_path: Path,
    output_path: Path,
    *,
    generated_at: datetime | None = None,
    logistic_artifact_dir: Path | None = None,
    arrival_tolerance_minutes: int = DEFAULT_ARRIVAL_TOLERANCE_MINUTES,
    source_time_tolerance_minutes: int = DEFAULT_SOURCE_TIME_TOLERANCE_MINUTES,
    max_predictions_per_lead: int | None = DEFAULT_MAX_PREDICTIONS_PER_LEAD,
) -> dict[str, Any]:
    """Write private prediction rows without labels or future-result features."""
    if arrival_tolerance_minutes < 0 or source_time_tolerance_minutes < 0:
        raise ValueError("shadow_time_tolerances_must_not_be_negative")
    if max_predictions_per_lead is not None and max_predictions_per_lead < 1:
        raise ValueError("max_predictions_per_lead_must_be_positive_or_none")
    config = load_training_config(training_config_path)
    if tuple(int(x) for x in config.label_dry_run.get("leadMinutes", LEADS)) != LEADS:
        raise ValueError("shadow_test_supports_fixed_30_and_60_minute_leads_only")
    generated_at = as_utc(generated_at or datetime.now(timezone.utc))
    dataset = read_dataset(input_path)
    groups = grouped_observations(dataset.by_lot)
    train_samples, train_rejections = build_training_samples(
        groups, config, generated_at, arrival_tolerance_minutes, source_time_tolerance_minutes
    )
    config_sha = sha256_file(training_config_path)
    artifacts = {
        lead: load_logistic_artifact(
            logistic_artifact_dir, lead, config, config_sha, dataset.zip_sha256,
            len(train_samples[lead]),
        )
        for lead in LEADS
    }

    predictions: list[dict[str, Any]] = []
    candidate_audit: dict[str, Any] = {}
    models_by_lead: dict[str, Any] = {}
    for lead in LEADS:
        candidates: list[ShadowObservation] = []
        rejected: Counter[str] = Counter()
        candidate_count = 0
        for lot_id in sorted(groups):
            for group in groups[lot_id]:
                if not group:
                    continue
                representative = representative_of(group)
                sample_row = representative or group[0]
                obs = sample_row.observation
                if config.split.split_for(taipei_date(obs.collected_at)) != "test":
                    continue
                candidate_count += 1
                reason = group_reason(group, source_time_tolerance_minutes, "as_of")
                if reason is None and obs.collected_at > generated_at:
                    reason = "as_of_timestamp_after_prediction_time"
                if reason is None and obs.is_unknown:
                    reason = "as_of_availability_unknown"
                if reason is None and (obs.available_car is None or obs.available_car < 0):
                    reason = "as_of_available_car_missing_or_negative"
                if reason:
                    rejected[reason] += 1
                else:
                    candidates.append(representative)

        selected = select_candidates(candidates, lead, max_predictions_per_lead)
        candidate_audit[str(lead)] = {
            "candidateCount": candidate_count,
            "qualifiedAsOfCandidateCount": len(candidates),
            "sampledPredictionCount": len(selected),
            "excludedBySampling": len(candidates) - len(selected),
            "rejectedAsOf": dict(sorted(rejected.items())),
        }
        train = train_samples[lead]
        alpha = float(config.baseline_experiment.get("calibratedPersistenceAlpha", 1.0))
        minimum_state = int(config.baseline_experiment.get("calibratedPersistenceMinStateSamples", 20))
        calibrated = CalibratedPersistenceModel.fit(train, alpha=alpha, minimum_state_samples=minimum_state) if train else None
        artifact, artifact_reason = artifacts[lead]
        models_by_lead[str(lead)] = {
            "current_state_persistence": {"status": "AVAILABLE", "modelVersion": "current_state_persistence_v1"},
            "calibrated_state_persistence_v1": (
                {"status": "AVAILABLE", "modelVersion": "calibrated_state_persistence_v1",
                 "trainingSamples": len(train), "alpha": alpha, "minimumStateSamples": minimum_state}
                if calibrated else {"status": "UNAVAILABLE", "reason": "no_qualified_train_samples"}
            ),
            "logistic_regression_v1": (
                {"status": "AVAILABLE", "modelVersion": artifact["modelId"],
                 "artifactSha256": sha256_file(Path(artifact["_path"])),
                 "decisionThreshold": float(artifact.get("decisionThreshold", 0.5))}
                if artifact else {"status": "UNAVAILABLE", "reason": artifact_reason}
            ),
        }
        for item in selected:
            obs = item.observation
            local = taipei_time(obs.collected_at)
            sample = LabelSample(
                split="test", lead_minutes=lead, entity_id=obs.lot_id,
                current_available=current_available(obs.available_car, obs.is_unknown),
                current_ratio=safe_ratio(obs.availability_ratio),
                weekday=local.weekday(), hour=local.hour, label=False,
                as_of_date=local.date().isoformat(),
            )
            predictions.append({
                "recordType": "prediction",
                "sampleId": hashlib.sha256(f"{item.fingerprint}:{lead}".encode()).hexdigest(),
                "parkingLotId": obs.lot_id,
                "asOfObservationFingerprint": item.fingerprint,
                "asOfAtUtc": iso_utc(obs.collected_at),
                "asOfDateTaipei": taipei_date(obs.collected_at).isoformat(),
                "asOfAvailableCar": obs.available_car,
                "asOfAvailabilityRatio": safe_ratio(obs.availability_ratio),
                "asOfQuality": "QUALIFIED",
                "sourceResponseAtUtc": iso_utc(item.source_response_at),
                "sourceTimeDeltaMinutes": source_time_delta(item),
                "leadMinutes": lead,
                "targetArrivalAtUtc": iso_utc(obs.collected_at + timedelta(minutes=lead)),
                "probabilities": {
                    "current_state_persistence": current_state_predictions(train, [sample])[0],
                    "calibrated_state_persistence_v1": calibrated.predict(sample) if calibrated else None,
                    "logistic_regression_v1": predict_logistic_regression(artifact["weights"], sample) if artifact else None,
                },
                "modelVersions": {
                    "current_state_persistence": "current_state_persistence_v1",
                    "calibrated_state_persistence_v1": "calibrated_state_persistence_v1" if calibrated else None,
                    "logistic_regression_v1": artifact["modelId"] if artifact else None,
                },
                "status": "EXPERIMENT_ONLY",
                "publishToOnlineSystem": False,
            })

    metadata = {
        "recordType": "metadata", "schemaVersion": SCHEMA_VERSION,
        "status": "EXPERIMENT_ONLY", "publishToOnlineSystem": False,
        "sourceType": SOURCE_TYPE, "sourceZipCount": dataset.zip_count,
        "sourceRows": dataset.source_rows, "parsedRows": dataset.parsed_rows,
        "parseSkippedRows": dataset.skipped_rows,
        "sourceZipSha256AtPrediction": dataset.zip_sha256,
        "trainingConfigSha256": config_sha, "generatedAtUtc": iso_utc(generated_at),
        "leadMinutes": list(LEADS), "arrivalToleranceMinutes": arrival_tolerance_minutes,
        "sourceTimestampDeltaToleranceMinutes": source_time_tolerance_minutes,
        "maxPredictionsPerLead": max_predictions_per_lead,
        "splitDates": split_metadata(config),
        "trainingSamplesByLead": {str(lead): len(train_samples[lead]) for lead in LEADS},
        "trainingRejectedByLead": {str(lead): dict(sorted(train_rejections[lead].items())) for lead in LEADS},
        "candidateAuditByLead": candidate_audit, "modelsByLead": models_by_lead,
        "labelsIncluded": False,
    }
    write_jsonl(output_path, [metadata, *predictions])
    return metadata


def verify_predictions(
    input_path: Path,
    prediction_path: Path,
    *,
    results_path: Path | None = None,
    report_json_path: Path | None = None,
    report_markdown_path: Path | None = None,
    verified_at: datetime | None = None,
) -> dict[str, Any]:
    """Pair only due predictions; a missing or invalid arrival stays unverifiable."""
    verified_at = as_utc(verified_at or datetime.now(timezone.utc))
    stored = read_jsonl(prediction_path)
    if not stored or stored[0].get("recordType") != "metadata":
        raise ValueError("shadow_prediction_metadata_missing")
    metadata = stored[0]
    if metadata.get("schemaVersion") != SCHEMA_VERSION or metadata.get("labelsIncluded") is not False:
        raise ValueError("unsupported_shadow_prediction_file")
    dataset = read_dataset(input_path)
    groups = grouped_observations(dataset.by_lot)
    timestamp_indexes = {
        lot_id: [group[0].observation.collected_at for group in lot_groups]
        for lot_id, lot_groups in groups.items()
    }
    by_identity = {
        (item.observation.lot_id, item.observation.collected_at, item.fingerprint): item
        for lot_groups in groups.values() for group in lot_groups for item in group
    }
    results = []
    for prediction in stored[1:]:
        if prediction.get("recordType") != "prediction":
            continue
        target = parse_aware_datetime(prediction["targetArrivalAtUtc"])
        result = dict(prediction)
        if target > verified_at:
            result.update({"verificationStatus": "PENDING", "verificationReason": "target_arrival_not_elapsed"})
            results.append(result)
            continue
        as_of_at = parse_aware_datetime(prediction["asOfAtUtc"])
        as_of = by_identity.get((prediction["parkingLotId"], as_of_at, prediction["asOfObservationFingerprint"]))
        if as_of is None:
            result.update({"verificationStatus": "UNVERIFIABLE", "verificationReason": "as_of_observation_missing_or_changed"})
            results.append(result)
            continue
        reason = group_reason([as_of], int(metadata["sourceTimestampDeltaToleranceMinutes"]), "as_of")
        if reason is None:
            reason = availability_reason(as_of.observation, "as_of")
        if reason:
            result.update({"verificationStatus": "UNVERIFIABLE", "verificationReason": reason})
            results.append(result)
            continue
        arrival, reason, delta = find_arrival(
            groups.get(prediction["parkingLotId"], []), as_of.observation, target,
            timedelta(minutes=int(metadata["arrivalToleranceMinutes"])),
            int(metadata["sourceTimestampDeltaToleranceMinutes"]), verified_at,
            timestamp_indexes.get(prediction["parkingLotId"], []),
        )
        if arrival is None:
            result.update({"verificationStatus": "UNVERIFIABLE", "verificationReason": reason or "arrival_observation_missing"})
            results.append(result)
            continue
        result.update({
            "verificationStatus": "VERIFIED", "verificationReason": None,
            "label": arrival.observation.available_car > 0,
            "arrivalObservedAtUtc": iso_utc(arrival.observation.collected_at),
            "arrivalDateTaipei": taipei_date(arrival.observation.collected_at).isoformat(),
            "arrivalAvailableCar": arrival.observation.available_car,
            "arrivalDeltaMinutes": delta,
        })
        results.append(result)

    report = aggregate_report(metadata, results, dataset, verified_at)
    if results_path:
        write_jsonl(results_path, [{"recordType": "metadata", "schemaVersion": "parking-offstreet-shadow-results-v1"}, *results])
    if report_json_path:
        report_json_path.parent.mkdir(parents=True, exist_ok=True)
        report_json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    if report_markdown_path:
        report_markdown_path.parent.mkdir(parents=True, exist_ok=True)
        report_markdown_path.write_text(render_markdown_report(report), encoding="utf-8")
    return report


def read_dataset(input_path: Path) -> Dataset:
    zip_paths = iter_zip_paths(input_path)
    digest = hashlib.sha256()
    for path in zip_paths:
        digest.update(path.name.encode("utf-8"))
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
    by_lot: dict[str, list[ShadowObservation]] = defaultdict(list)
    source_rows = parsed_rows = skipped_rows = 0
    for row in iter_collector_rows(input_path):
        if row.dataset != "offstreet":
            continue
        source_rows += 1
        observation = parse_offstreet(row)
        if observation is None:
            skipped_rows += 1
            continue
        source_text = value(row.values, "source_api_response_at_utc")
        item = ShadowObservation(
            observation, parse_datetime(source_text), source_text, has_explicit_timezone(source_text)
        )
        by_lot[observation.lot_id].append(item)
        parsed_rows += 1
    for rows in by_lot.values():
        rows.sort(key=lambda item: (item.observation.collected_at, item.fingerprint))
    return Dataset(by_lot, source_rows, parsed_rows, skipped_rows, len(zip_paths), digest.hexdigest())


def grouped_observations(by_lot: dict[str, list[ShadowObservation]]) -> dict[str, list[list[ShadowObservation]]]:
    result = {}
    for lot_id, rows in by_lot.items():
        groups: dict[datetime, list[ShadowObservation]] = defaultdict(list)
        for row in rows:
            groups[row.observation.collected_at].append(row)
        result[lot_id] = [sorted(groups[at], key=lambda item: item.fingerprint) for at in sorted(groups)]
    return result


def representative_of(group: list[ShadowObservation]) -> ShadowObservation | None:
    return group[0] if group and len({row.fingerprint for row in group}) == 1 else None


_REJECTION_PRIORITY = {
    "collected_at_timezone_missing": 0,
    "collected_date_taipei_missing": 1,
    "collected_date_taipei_mismatch": 2,
    "source_timestamp_missing": 3,
    "source_timestamp_timezone_missing": 4,
    "source_timestamp_invalid": 5,
    "source_observation_stale": 6,
    "source_timestamp_too_far_in_future": 7,
}


def source_time_reason(item: ShadowObservation, tolerance_minutes: int) -> str | None:
    if not item.source_response_text:
        return "source_timestamp_missing"
    if not item.source_timestamp_explicit:
        return "source_timestamp_timezone_missing"
    if item.source_response_at is None:
        return "source_timestamp_invalid"
    delta = (item.observation.collected_at - item.source_response_at).total_seconds() / 60
    if delta > tolerance_minutes:
        return "source_observation_stale"
    if delta < -tolerance_minutes:
        return "source_timestamp_too_far_in_future"
    return None


def group_reason(group: list[ShadowObservation], source_tolerance: int, role: str) -> str | None:
    reasons = []
    for item in group:
        reason = strict_time_rejection_reason(item.observation) or source_time_reason(item, source_tolerance)
        if reason:
            reasons.append(reason)
    if reasons:
        return min(reasons, key=lambda item: _REJECTION_PRIORITY.get(item, 99))
    return f"{role}_duplicate_conflict" if len({row.fingerprint for row in group}) > 1 else None


def availability_reason(observation: OffstreetObservation, role: str) -> str | None:
    if observation.is_unknown:
        return f"{role}_availability_unknown"
    if observation.available_car is None:
        return f"{role}_available_car_missing"
    if observation.available_car < 0:
        return f"{role}_available_car_negative"
    return None


def build_training_samples(
    groups: dict[str, list[list[ShadowObservation]]], config: Any,
    generated_at: datetime, arrival_tolerance: int, source_tolerance: int,
) -> tuple[dict[int, list[LabelSample]], dict[int, Counter[str]]]:
    samples = {lead: [] for lead in LEADS}
    rejected = {lead: Counter() for lead in LEADS}
    for lot_id in sorted(groups):
        lot_groups = groups[lot_id]
        timestamps = [group[0].observation.collected_at for group in lot_groups]
        for group in lot_groups:
            item = representative_of(group)
            if item is None:
                continue
            obs = item.observation
            if config.split.split_for(taipei_date(obs.collected_at)) != "train":
                continue
            reason = group_reason(group, source_tolerance, "as_of")
            if reason is None and not obs.is_unknown and (obs.available_car is None or obs.available_car < 0):
                reason = "as_of_available_car_missing_or_negative"
            if reason is None and obs.collected_at > generated_at:
                reason = "as_of_timestamp_after_prediction_time"
            for lead in LEADS:
                if reason:
                    rejected[lead][reason] += 1
                    continue
                arrival, why, _ = find_arrival(
                    lot_groups, obs, obs.collected_at + timedelta(minutes=lead),
                    timedelta(minutes=arrival_tolerance), source_tolerance, generated_at, timestamps,
                )
                if arrival is None:
                    rejected[lead][why or "arrival_observation_missing"] += 1
                    continue
                if config.split.split_for(taipei_date(arrival.observation.collected_at)) != "train":
                    rejected[lead]["arrival_crosses_split_boundary"] += 1
                    continue
                local = taipei_time(obs.collected_at)
                samples[lead].append(LabelSample(
                    split="train", lead_minutes=lead, entity_id=lot_id,
                    current_available=current_available(obs.available_car, obs.is_unknown),
                    current_ratio=safe_ratio(obs.availability_ratio),
                    weekday=local.weekday(), hour=local.hour,
                    label=arrival.observation.available_car > 0,
                    as_of_date=local.date().isoformat(),
                ))
    return samples, rejected


def find_arrival(
    lot_groups: list[list[ShadowObservation]], as_of: OffstreetObservation,
    target: datetime, tolerance: timedelta, source_tolerance: int,
    observed_through: datetime, timestamps: list[datetime] | None = None,
) -> tuple[ShadowObservation | None, str | None, float | None]:
    matches = []
    ordered_timestamps = timestamps or [group[0].observation.collected_at for group in lot_groups]
    lower = bisect_left(ordered_timestamps, target - tolerance)
    upper = bisect_right(ordered_timestamps, target + tolerance)
    for index in range(lower, upper):
        group = lot_groups[index]
        timestamp = ordered_timestamps[index]
        if timestamp <= as_of.collected_at or timestamp > observed_through:
            continue
        delta = abs((timestamp - target).total_seconds())
        if delta <= tolerance.total_seconds():
            matches.append((delta, timestamp, group))
    if not matches:
        return None, "arrival_observation_missing", None
    _, timestamp, group = min(matches, key=lambda item: (item[0], item[1]))
    reason = group_reason(group, source_tolerance, "arrival")
    item = representative_of(group)
    if reason is None and item is not None:
        reason = availability_reason(item.observation, "arrival")
    if reason:
        return None, reason, None
    if item is None:
        return None, "arrival_duplicate_conflict", None
    return item, None, (timestamp - target).total_seconds() / 60


def select_candidates(rows: list[ShadowObservation], lead: int, limit: int | None) -> list[ShadowObservation]:
    ranked = sorted(rows, key=lambda item: hashlib.sha256(
        f"{item.observation.lot_id}|{iso_utc(item.observation.collected_at)}|{lead}".encode()
    ).hexdigest())
    return ranked if limit is None else ranked[:limit]


def load_logistic_artifact(
    artifact_dir: Path | None, lead: int, config: Any, config_sha: str, source_sha: str,
    expected_training_samples: int,
) -> tuple[dict[str, Any] | None, str]:
    if artifact_dir is None:
        return None, "logistic_artifact_directory_not_configured"
    path = Path(artifact_dir) / f"offstreet-lead-{lead}m-logistic-v2.json"
    if not path.is_file():
        return None, "logistic_artifact_missing_for_lead"
    try:
        artifact = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None, "logistic_artifact_unreadable"
    checks = [
        (artifact.get("schemaVersion") == "parking-logistic-experiment-v2", "logistic_artifact_schema_mismatch"),
        (artifact.get("featureVersion") == "parking_features_as_of_v2" and artifact.get("featureNames") == FEATURE_NAMES, "logistic_artifact_feature_version_mismatch"),
        (artifact.get("status") == "EXPERIMENT_ONLY" and artifact.get("publishToOnlineSystem") is False, "logistic_artifact_publication_policy_invalid"),
        (artifact.get("dataset") == "offstreet" and artifact.get("coveragePolicyVersion") == "OFFSTREET", "logistic_artifact_dataset_mismatch"),
        (artifact.get("target") == "arrival_has_confirmed_availability" and int(artifact.get("leadMinutes", -1)) == lead, "logistic_artifact_target_or_lead_mismatch"),
        (artifact.get("sourceZipSha256") == source_sha, "logistic_artifact_source_hash_mismatch"),
        (artifact.get("trainingConfigSha256") == config_sha, "logistic_artifact_config_hash_mismatch"),
    ]
    for valid, reason in checks:
        if not valid:
            return None, reason
    expected = split_metadata(config)
    for key, field in (("train", "trainingDateRange"), ("validation", "validationDateRange"), ("test", "testDateRange")):
        if artifact.get(field) != expected[key]:
            return None, f"logistic_artifact_{key}_dates_mismatch"
    weights = artifact.get("weights")
    if not isinstance(weights, list) or len(weights) != len(FEATURE_NAMES) or any(not finite(x) for x in weights):
        return None, "logistic_artifact_weights_invalid"
    if not artifact.get("modelId") or int(artifact.get("trainingSamples", 0)) != expected_training_samples:
        return None, "logistic_artifact_training_metadata_invalid"
    threshold = artifact.get("decisionThreshold")
    if not finite(threshold) or not 0 <= float(threshold) <= 1:
        return None, "logistic_artifact_threshold_invalid"
    artifact["weights"] = [float(x) for x in weights]
    artifact["_path"] = str(path)
    return artifact, ""


def aggregate_report(metadata: dict[str, Any], rows: list[dict[str, Any]], dataset: Dataset, verified_at: datetime) -> dict[str, Any]:
    by_lead = {}
    all_verified = []
    totals = Counter()
    for lead in LEADS:
        key = str(lead)
        audit = metadata["candidateAuditByLead"][key]
        members = [row for row in rows if int(row.get("leadMinutes", -1)) == lead]
        verified = [row for row in members if row.get("verificationStatus") == "VERIFIED"]
        unresolved = [row for row in members if row.get("verificationStatus") == "UNVERIFIABLE"]
        pending = [row for row in members if row.get("verificationStatus") == "PENDING"]
        rejected = audit["rejectedAsOf"]
        available = [name for name in MODEL_NAMES if metadata["modelsByLead"][key][name]["status"] == "AVAILABLE"]
        common = [row for row in verified if all(probability(row, name) is not None for name in available)]
        labels = [bool(row["label"]) for row in common]
        dates = {row["arrivalDateTaipei"] for row in common}
        methods = {}
        for name in MODEL_NAMES:
            if name not in available:
                methods[name] = {
                    "status": "UNAVAILABLE", "reason": metadata["modelsByLead"][key][name].get("reason", "no_qualified_predictions"),
                    "samples": 0, "brierScore": None, "falsePositiveRate": None, "precision": None,
                }
            else:
                methods[name] = method_metrics(
                    common, name, float(metadata["modelsByLead"][key][name].get("decisionThreshold", 0.5))
                )
        exploratory = []
        if len(common) < EXPLORATORY_MINIMUM_SAMPLES:
            exploratory.append("fewer_than_50_verified_cases_for_lead")
        if len(set(labels)) < 2:
            exploratory.append("verified_labels_do_not_include_both_classes")
        if len(dates) < EXPLORATORY_MINIMUM_DISTINCT_DATES:
            exploratory.append("fewer_than_5_verified_dates")
        by_lead[key] = {
            "candidateCount": audit["candidateCount"],
            "qualifiedAsOfCandidateCount": audit["qualifiedAsOfCandidateCount"],
            "sampledPredictionCount": audit["sampledPredictionCount"],
            "excludedBySampling": audit["excludedBySampling"],
            "rejectedAsOfCount": sum(rejected.values()),
            "rejectedAsOfReasons": rejected,
            "verifiedCount": len(common),
            "unverifiableCount": len(unresolved),
            "unverifiableReasons": dict(sorted(Counter(row.get("verificationReason", "unknown") for row in unresolved).items())),
            "pendingCount": len(pending),
            "positiveCount": sum(labels),
            "negativeCount": len(labels) - sum(labels),
            "differentDates": len(dates),
            "differentParkingLots": len({row["parkingLotId"] for row in common}),
            "modelMetrics": methods,
            "status": "EXPLORATORY_ONLY",
            "exploratoryReasons": exploratory,
        }
        all_verified.extend(common)
        totals.update({
            "candidateCount": audit["candidateCount"],
            "sampledPredictionCount": audit["sampledPredictionCount"],
            "rejectedAsOfCount": sum(rejected.values()),
            "unverifiableCount": len(unresolved),
            "pendingCount": len(pending),
        })
    labels = [bool(row["label"]) for row in all_verified]
    dates = {row["arrivalDateTaipei"] for row in all_verified}
    overall_reasons = []
    if len(all_verified) < EXPLORATORY_MINIMUM_SAMPLES:
        overall_reasons.append("fewer_than_50_verified_cases")
    if len(set(labels)) < 2:
        overall_reasons.append("verified_labels_do_not_include_both_classes")
    if len(dates) < EXPLORATORY_MINIMUM_DISTINCT_DATES:
        overall_reasons.append("fewer_than_5_verified_dates")
    return {
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "status": "EXPLORATORY_ONLY",
        "source": {
            "type": SOURCE_TYPE, "sourceZipCountAtPrediction": metadata["sourceZipCount"],
            "sourceRowsAtPrediction": metadata["sourceRows"], "parsedRowsAtPrediction": metadata["parsedRows"],
            "parseSkippedRowsAtPrediction": metadata["parseSkippedRows"],
            "sourceZipSha256AtPrediction": metadata["sourceZipSha256AtPrediction"],
            "sourceZipSha256AtVerification": dataset.zip_sha256,
        },
        "policy": {
            "leadMinutes": list(LEADS),
            "arrivalObservationToleranceMinutes": metadata["arrivalToleranceMinutes"],
            "sourceTimestampDeltaToleranceMinutes": metadata["sourceTimestampDeltaToleranceMinutes"],
            "maxPredictionsPerLead": metadata["maxPredictionsPerLead"],
            "sampling": "deterministic hash rank by private lot ID, as-of timestamp, and lead; no labels used",
            "outcomeRule": "available_car > 0 is positive; == 0 is negative; unknown/negative/stale/mismatched/missing is unverifiable",
        },
        "predictionState": {
            "status": "EXPERIMENT_ONLY", "publishToOnlineSystem": False,
            "onlinePredictionsWritten": False, "onlineModelVersion": None, "onlineProbability": None,
        },
        "generatedAtUtc": metadata["generatedAtUtc"], "verifiedAtUtc": iso_utc(verified_at),
        "candidateCount": totals["candidateCount"], "sampledPredictionCount": totals["sampledPredictionCount"],
        "verifiedCount": len(all_verified), "unverifiableCount": totals["unverifiableCount"],
        "rejectedAsOfCount": totals["rejectedAsOfCount"], "pendingCount": totals["pendingCount"],
        "differentDates": len(dates), "differentParkingLots": len({row["parkingLotId"] for row in all_verified}),
        "exploratoryReasons": overall_reasons, "modelsByLead": metadata["modelsByLead"],
        "byLead": by_lead,
        "limitations": [
            "本報告使用本機 collector ZIP 離線代理觀測，不代表主系統 PUBLISHED 歷史觀測。",
            "抵達標籤取自目標時間附近的觀測；已列明時間差上限，近似觀測不代表精確抵達瞬間。",
            "少量日期／樣本、單一類別及場站內相關觀測限制解讀；EXPLORATORY_ONLY 不代表統計准入或上線建議。",
            "本次只比較固定 30／60 分鐘 lead，不代表任意 Google ETA 的預測能力。",
        ],
    }


def method_metrics(rows: list[dict[str, Any]], name: str, threshold: float) -> dict[str, Any]:
    if not rows:
        return {"status": "UNAVAILABLE", "reason": "no_verified_common_samples", "samples": 0,
                "brierScore": None, "falsePositiveRate": None, "precision": None}
    pairs = [(bool(row["label"]), float(probability(row, name))) for row in rows]
    tp = sum(p >= threshold and y for y, p in pairs)
    fp = sum(p >= threshold and not y for y, p in pairs)
    tn = sum(p < threshold and not y for y, p in pairs)
    return {
        "status": "AVAILABLE", "samples": len(pairs),
        "positive": sum(y for y, _ in pairs), "negative": sum(not y for y, _ in pairs),
        "brierScore": sum((p - float(y)) ** 2 for y, p in pairs) / len(pairs),
        "falsePositiveRate": fp / (fp + tn) if fp + tn else None,
        "precision": tp / (tp + fp) if tp + fp else None,
        "truePositive": tp, "falsePositive": fp, "trueNegative": tn,
        "falseNegative": sum(p < threshold and y for y, p in pairs),
    }


def render_markdown_report(report: dict[str, Any]) -> str:
    lines = [
        "# 路外停車 Shadow Test MVP 摘要", "",
        f"- 狀態：{report['status']}",
        f"- 資料來源：{report['source']['type']}；ZIP {report['source']['sourceZipCountAtPrediction']} 個、路外原始列 {report['source']['sourceRowsAtPrediction']} 列。",
        f"- 抵達觀測容忍：±{report['policy']['arrivalObservationToleranceMinutes']} 分鐘；來源時間與收集時間容忍：±{report['policy']['sourceTimestampDeltaToleranceMinutes']} 分鐘。",
        f"- 候選 {report['candidateCount']}；抽樣預測 {report['sampledPredictionCount']}；可驗證 {report['verifiedCount']}；不可驗證 {report['unverifiableCount']}；as-of 拒收 {report['rejectedAsOfCount']}；尚未到期 {report['pendingCount']}。",
        f"- 覆蓋日期 {report['differentDates']} 天、不同停車場 {report['differentParkingLots']} 座。",
        "- Shadow 狀態為 EXPERIMENT_ONLY；未寫入正式 predictions。", "",
        "## 各 lead 摘要", "",
        "| Lead | 候選 | 合格 as-of | 抽樣預測 | 可驗證 | 不可驗證 | 拒收 | 待到期 | 日期 | 停車場 |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for lead in LEADS:
        row = report["byLead"][str(lead)]
        lines.append(
            f"| {lead}m | {row['candidateCount']} | {row['qualifiedAsOfCandidateCount']} | {row['sampledPredictionCount']} | {row['verifiedCount']} | {row['unverifiableCount']} | {row['rejectedAsOfCount']} | {row['pendingCount']} | {row['differentDates']} | {row['differentParkingLots']} |"
        )
    lines.extend(["", "## 相同可驗證樣本上的方法比較", "",
                  "| Lead | 方法 | n | Brier | FPR | Precision | 狀態／原因 |",
                  "| ---: | --- | ---: | ---: | ---: | ---: | --- |"])
    for lead in LEADS:
        for name in MODEL_NAMES:
            metric = report["byLead"][str(lead)]["modelMetrics"][name]
            if metric["status"] == "UNAVAILABLE":
                lines.append(f"| {lead}m | {name} | 0 | N/A | N/A | N/A | {metric['reason']} |")
            else:
                lines.append(f"| {lead}m | {name} | {metric['samples']} | {fmt(metric['brierScore'])} | {fmt(metric['falsePositiveRate'])} | {fmt(metric['precision'])} | EXPLORATORY_ONLY |")
    lines.extend(["", "## 不可驗證與拒收原因", ""])
    for lead in LEADS:
        row = report["byLead"][str(lead)]
        reasons = Counter(row["rejectedAsOfReasons"])
        reasons.update(row["unverifiableReasons"])
        lines.append(f"- {lead}m：" + (", ".join(f"{reason}={count}" for reason, count in sorted(reasons.items())) if reasons else "無"))
    lines.extend(["", "## 限制", "", *[f"- {item}" for item in report["limitations"]], ""])
    return "\n".join(lines)


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows), encoding="utf-8")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def source_time_delta(item: ShadowObservation) -> float | None:
    if item.source_response_at is None:
        return None
    return round((item.observation.collected_at - item.source_response_at).total_seconds() / 60, 3)


def split_metadata(config: Any) -> dict[str, Any]:
    def item(date_range: Any) -> dict[str, str] | None:
        return None if date_range is None else {"start": date_range.start.isoformat(), "end": date_range.end.isoformat()}
    return {"train": item(config.split.train), "validation": item(config.split.validation), "test": item(config.split.test)}


def finite(value: Any) -> bool:
    try:
        return math.isfinite(float(value))
    except (TypeError, ValueError):
        return False


def safe_ratio(value: float | None) -> float | None:
    if value is None or not finite(value):
        return None
    return current_ratio(value)


def probability(row: dict[str, Any], name: str) -> float | None:
    value = row.get("probabilities", {}).get(name)
    return float(value) if value is not None and finite(value) else None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def iso_utc(value: datetime | None) -> str | None:
    return None if value is None else as_utc(value).isoformat().replace("+00:00", "Z")


def as_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("shadow_timestamp_must_include_timezone")
    return value.astimezone(timezone.utc)


def parse_aware_datetime(text: str) -> datetime:
    return as_utc(datetime.fromisoformat(text.replace("Z", "+00:00")))


def fmt(value: float | None) -> str:
    return "N/A" if value is None else f"{value:.4f}"
