from __future__ import annotations

import csv
import json
import math
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import (
    COVERAGE_POLICY_COMPLETE,
    COVERAGE_POLICY_LEGACY,
    DATE_POLICY_TIMESTAMP_TAIPEI_V1,
    LabelSample,
    build_offstreet_samples,
    build_roadside_samples,
    current_state_predictions,
    features,
    RoadsideCoverageAudit,
)
from parking_ml.calibrated_experiment import (
    FEATURE_NAMES,
    CalibratedPersistenceModel,
    calibration_summary,
    daily_summary,
    evaluate_validation_then_test,
    logistic_artifact,
    metric_summary,
    selection_gate,
    test_evaluation_gate,
    run_calibrated_experiment,
)
from parking_ml.dataset_split import DateRange
from parking_ml.label_dry_run import (
    LabelDryRunSettings,
    OffstreetObservation,
    RoadsideObservation,
    LabelStats,
    settings_from_config,
)
from parking_ml.training_config import load_training_config


def sample(split: str, label: bool, state: bool | None, *, ratio: float | None = 0.5,
           day: str = "2026-10-22", hour: int = 8) -> LabelSample:
    return LabelSample(split, 30, "lot", state, ratio, 0, hour, label, day)


class CalibratedExperimentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = load_training_config(ROOT / "config" / "training-config-80-10-10.example.json")
        self.settings = LabelDryRunSettings((30,), timedelta(minutes=15), 500, 50, False)

    def test_calibration_is_train_only_and_validation_test_labels_cannot_fit(self) -> None:
        train = [sample("train", True, True), sample("train", False, True), sample("train", False, False)]
        fitted = CalibratedPersistenceModel.fit(train, alpha=1, minimum_state_samples=1)
        altered_holdout = [sample("validation", False, True), sample("test", True, False)]
        self.assertEqual(fitted, CalibratedPersistenceModel.fit(train, alpha=1, minimum_state_samples=1))
        with self.assertRaisesRegex(ValueError, "train_split_only"):
            CalibratedPersistenceModel.fit(train + altered_holdout, alpha=1, minimum_state_samples=1)

    def test_true_false_unknown_smoothing_and_low_sample_fallback(self) -> None:
        train = [
            sample("train", True, True), sample("train", False, True),
            sample("train", False, False), sample("train", True, None),
        ]
        model = CalibratedPersistenceModel.fit(train, alpha=1, minimum_state_samples=1)
        self.assertAlmostEqual(2 / 4, model.predict(sample("test", False, True)))
        self.assertAlmostEqual(1 / 3, model.predict(sample("test", False, False)))
        self.assertAlmostEqual(2 / 3, model.predict(sample("test", False, None)))
        fallback_model = CalibratedPersistenceModel.fit(train, alpha=1, minimum_state_samples=5)
        self.assertAlmostEqual(0.5, fallback_model.fallback_probability)
        self.assertEqual({}, fallback_model.state_probabilities)
        self.assertAlmostEqual(0.5, fallback_model.predict(sample("test", True, False)))

    def test_empty_single_class_and_extreme_rates_stay_finite(self) -> None:
        self.assertAlmostEqual(0.5, CalibratedPersistenceModel.fit([]).fallback_probability)
        all_positive = [sample("train", True, True) for _ in range(4)]
        model = CalibratedPersistenceModel.fit(all_positive, alpha=1, minimum_state_samples=1)
        self.assertAlmostEqual(5 / 6, model.predict(sample("test", False, True)))
        self.assertTrue(math.isfinite(model.predict(sample("test", False, None))))
        for alpha in (0, float("nan"), float("inf")):
            with self.assertRaisesRegex(ValueError, "laplace_alpha"):
                CalibratedPersistenceModel.fit(all_positive, alpha=alpha)

    def test_metrics_report_undefined_denominators_as_none(self) -> None:
        actual_positive = [sample("test", True, False, ratio=0.05)] * 2
        summary = metric_summary(actual_positive, [0.1, 0.1])
        self.assertIsNone(summary["false_positive_rate"])
        self.assertIsNone(summary["precision"])
        self.assertEqual(0, summary["near_full"]["predicted_positive"])
        self.assertIsNone(summary["near_full"]["precision"])
        self.assertIsNone(summary["near_full"]["false_positive_rate"])
        empty_subset = metric_summary([sample("test", False, False, ratio=0.2)], [0.1])
        self.assertIsNone(empty_subset["near_full"]["brier"])

    def test_daily_and_calibration_aggregates_and_low_sample_bucket(self) -> None:
        samples = [
            sample("test", True, False, day="2026-10-22"),
            sample("test", False, False, day="2026-10-22"),
            sample("test", False, True, day="2026-10-23"),
        ]
        probabilities = [0.8, 0.2, 0.2]
        daily = daily_summary(samples, probabilities)
        self.assertEqual(2, daily["2026-10-22"]["samples"])
        self.assertEqual(0.5, daily["2026-10-22"]["positive_rate"])
        buckets = calibration_summary(samples, probabilities, bins=10, minimum=3)
        self.assertEqual({"LOW_SAMPLE"}, {row["status"] for row in buckets})
        self.assertEqual(3, sum(row["samples"] for row in buckets))

    def test_gate_checks_train_validation_first_then_test_gate(self) -> None:
        self.config.baseline_experiment.update(minTrainSamples=2, minValidationSamples=2, minTestSamples=2)
        train = [sample("train", True, True), sample("train", False, False)]
        validation = [sample("validation", True, True), sample("validation", False, False)]
        self.assertIsNone(selection_gate(train, validation, self.config))
        self.assertEqual("INSUFFICIENT_TEST_SAMPLES", test_evaluation_gate([], self.config))
        self.assertEqual("VALIDATION_SINGLE_CLASS", selection_gate(train, [sample("validation", True, True)] * 2, self.config))
        self.assertEqual("TRAIN_SINGLE_CLASS", selection_gate([sample("train", True, True)] * 2, validation, self.config))

    def test_runner_does_not_open_test_until_validation_selection(self) -> None:
        self.config.baseline_experiment.update(minTrainSamples=2, minValidationSamples=2, minTestSamples=2)
        train = [sample("train", True, True), sample("train", False, False)]
        validation = [sample("validation", True, True), sample("validation", False, False)]
        test = [sample("test", True, True), sample("test", False, False)]
        model = CalibratedPersistenceModel.fit(train, minimum_state_samples=1)
        observed_splits: list[str] = []

        def fake_predict(train_rows, holdout, config, fitted):
            observed_splits.append(holdout[0].split if holdout else "empty")
            return {name: [0.6, 0.4][:len(holdout)] for name in (
                "train_positive_rate", "current_state_persistence",
                "calibrated_state_persistence_v1", "logistic_regression_v1",
            )}

        with patch("parking_ml.calibrated_experiment.predict_all", side_effect=fake_predict):
            result = evaluate_validation_then_test(train, validation, test, self.config, model)
        self.assertIsNotNone(result["selected"])
        self.assertEqual(["validation", "test"], observed_splits)

        self.config.baseline_experiment["minValidationSamples"] = 3
        observed_splits.clear()
        with patch("parking_ml.calibrated_experiment.predict_all", side_effect=fake_predict):
            blocked = evaluate_validation_then_test(train, validation, test, self.config, model)
        self.assertEqual("INSUFFICIENT_VALIDATION_SAMPLES", blocked["gate"])
        self.assertIsNone(blocked["selected"])
        self.assertEqual(["validation"], observed_splits)
        self.assertEqual("NOT_OPENED_BEFORE_VALIDATION_SELECTION", blocked["test_gate"])

    def test_feature_contract_and_artifact_schema_match_as_of_hour(self) -> None:
        current = sample("train", True, True, hour=6)
        vector = features(current)
        self.assertEqual(len(FEATURE_NAMES), len(vector))
        self.assertEqual("as_of_hour_sin", FEATURE_NAMES[4])
        self.assertAlmostEqual(1.0, vector[4])
        self.assertAlmostEqual(0.0, vector[5])
        payload = logistic_artifact(
            cohort="offstreet", policy="not_applicable", lead=30, weights=[0.0] * len(FEATURE_NAMES),
            train=[sample("train", True, True), sample("train", False, False)], config=self.config,
            source_hash="source", config_hash="config",
        )
        self.assertEqual("parking-logistic-experiment-v2", payload["schemaVersion"])
        self.assertEqual("parking_features_as_of_v2", payload["featureVersion"])
        self.assertEqual(FEATURE_NAMES, payload["featureNames"])
        self.assertFalse(payload["publishToOnlineSystem"])
        self.assertEqual("EXPERIMENT_ONLY", payload["status"])

    def test_fixed_persistence_remains_095_005_and_unknown_uses_train_rate(self) -> None:
        train = [sample("train", True, None), sample("train", False, None)]
        holdout = [sample("test", True, True), sample("test", False, False), sample("test", True, None)]
        self.assertEqual([0.95, 0.05, 0.5], current_state_predictions(train, holdout))

    def test_split_boundary_arrival_is_rejected_and_features_use_taipei_time(self) -> None:
        lot_rows = [
            OffstreetObservation("l", datetime(2026, 10, 18, 15, 45, tzinfo=timezone.utc), date(2026, 10, 18), 2, 0.2, False, True),
            OffstreetObservation("l", datetime(2026, 10, 18, 16, 15, tzinfo=timezone.utc), date(2026, 10, 19), 1, 0.1, False, True),
        ]
        admission: dict[tuple[str, int], LabelStats] = {}
        samples = build_offstreet_samples({"l": lot_rows}, self.config, self.settings,
                                          strict_split_boundaries=True, admission_audit=admission)
        self.assertEqual([], samples)
        self.assertEqual(1, admission[("train", 30)].rejected["arrival_crosses_split_boundary"])
        same_split = [
            lot_rows[0],
            OffstreetObservation("l", datetime(2026, 10, 18, 16, 15, tzinfo=timezone.utc), date(2026, 10, 18), 1, 0.1, False, True),
        ]
        accepted = build_offstreet_samples({"l": same_split}, self.config, self.settings, strict_split_boundaries=True)
        self.assertEqual(1, len(accepted))
        self.assertEqual(23, accepted[0].hour)
        self.assertEqual("2026-10-18", accepted[0].as_of_date)

        strict_admission: dict[tuple[str, int], LabelStats] = {}
        strict_samples = build_offstreet_samples(
            {"l": same_split}, self.config, self.settings,
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
            admission_audit=strict_admission,
        )
        self.assertEqual([], strict_samples)
        self.assertEqual(1, strict_admission[("train", 30)].rejected["collected_date_taipei_mismatch"])
        self.assertEqual(1, strict_admission[("validation", 30)].rejected["collected_date_taipei_mismatch"])

    def test_timestamp_authoritative_offstreet_policy_checks_dates_and_preserves_legacy(self) -> None:
        def run(rows, **kwargs):
            audit: dict[tuple[str, int], LabelStats] = {}
            samples = build_offstreet_samples({"lot": rows}, self.config, self.settings, admission_audit=audit, **kwargs)
            return samples, audit

        # UTC and Taipei dates differ, but the declared Taipei date matches and is accepted.
        valid, _ = run(
            [
                offstreet_observation("2026-10-18T16:15:00Z", "2026-10-19"),
                offstreet_observation("2026-10-18T16:45:00Z", "2026-10-19", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual(1, len(valid))
        self.assertEqual("validation", valid[0].split)
        self.assertEqual("2026-10-19", valid[0].as_of_date)
        self.assertEqual(0, valid[0].hour)

        # An as-of date mismatch is rejected before its arrival can create a label.
        as_of_mismatch, as_of_audit = run(
            [
                offstreet_observation("2026-10-18T16:15:00Z", "2026-10-18"),
                offstreet_observation("2026-10-18T16:45:00Z", "2026-10-19", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual([], as_of_mismatch)
        self.assertEqual(1, as_of_audit[("validation", 30)].rejected["collected_date_taipei_mismatch"])

        # An arrival date mismatch is rejected even when its file_date would keep it in train.
        arrival_mismatch, arrival_audit = run(
            [
                offstreet_observation("2026-10-18T16:15:00Z", "2026-10-19"),
                offstreet_observation("2026-10-18T16:45:00Z", "2026-10-18", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual([], arrival_mismatch)
        self.assertEqual(2, arrival_audit[("validation", 30)].rejected["collected_date_taipei_mismatch"])

        # Correct metadata still cannot move a train label across the validation boundary.
        crossed, crossed_audit = run(
            [
                offstreet_observation("2026-10-18T15:45:00Z", "2026-10-18"),
                offstreet_observation("2026-10-18T16:15:00Z", "2026-10-19", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual([], crossed)
        self.assertEqual(1, crossed_audit[("train", 30)].rejected["arrival_crosses_split_boundary"])

        missing_zone, zone_audit = run(
            [
                offstreet_observation("2026-10-19T00:00:00Z", "2026-10-19", timezone_explicit=False),
                offstreet_observation("2026-10-19T00:30:00Z", "2026-10-19", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual([], missing_zone)
        self.assertEqual(1, zone_audit[("validation", 30)].rejected["collected_at_timezone_missing"])

        missing_date, date_audit = run(
            [
                offstreet_observation("2026-10-19T00:00:00Z", "2026-10-19", file_date_explicit=False),
                offstreet_observation("2026-10-19T00:30:00Z", "2026-10-19", available=0),
            ],
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        self.assertEqual([], missing_date)
        self.assertEqual(1, date_audit[("validation", 30)].rejected["collected_date_taipei_missing"])

    def test_timestamp_authoritative_roadside_policy_checks_as_of_arrival_and_split(self) -> None:
        def run(rows):
            data = {"spot": [roadside_observation("2026-09-24T00:00:00Z", "2026-09-24"), *rows]}
            audit: dict[tuple[str, int], LabelStats] = {}
            samples = build_roadside_samples(
                data, self.config, self.settings, coverage_policy_version=COVERAGE_POLICY_COMPLETE,
                date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1, admission_audit=audit,
            )
            return samples, audit

        as_of_mismatch, as_of_audit = run([
            roadside_observation("2026-10-18T16:15:00Z", "2026-10-18"),
            roadside_observation("2026-10-18T16:45:00Z", "2026-10-19", available=False),
        ])
        self.assertEqual([], as_of_mismatch)
        self.assertEqual(1, as_of_audit[("validation", 30)].rejected["collected_date_taipei_mismatch"])

        arrival_mismatch, arrival_audit = run([
            roadside_observation("2026-10-19T00:00:00Z", "2026-10-19"),
            roadside_observation("2026-10-19T00:30:00Z", "2026-10-18", available=False),
        ])
        self.assertEqual([], arrival_mismatch)
        self.assertEqual(2, arrival_audit[("validation", 30)].rejected["collected_date_taipei_mismatch"])

        valid, _ = run([
            roadside_observation("2026-10-18T16:15:00Z", "2026-10-19"),
            roadside_observation("2026-10-18T16:45:00Z", "2026-10-19", available=False),
        ])
        self.assertEqual(1, len(valid))
        self.assertEqual("validation", valid[0].split)
        self.assertEqual("2026-10-19", valid[0].as_of_date)
        self.assertEqual(0, valid[0].hour)

        crossed, crossed_audit = run([
            roadside_observation("2026-10-18T15:45:00Z", "2026-10-18"),
            roadside_observation("2026-10-18T16:15:00Z", "2026-10-19", available=False),
        ])
        self.assertEqual([], crossed)
        self.assertEqual(1, crossed_audit[("train", 30)].rejected["arrival_crosses_split_boundary"])

    def test_small_zip_runs_calibrated_pipeline_and_honors_legacy_policy_flag(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            input_directory = root / "collector"
            input_directory.mkdir()
            write_calibrated_fixture(input_directory)

            config_data = json.loads((ROOT / "config" / "training-config-80-10-10.example.json").read_text(encoding="utf-8"))
            config_data["labelDryRun"]["leadMinutes"] = [30]
            config_data["baselineExperiment"].update(
                minTrainSamples=2, minValidationSamples=2, minTestSamples=2,
                calibratedPersistenceMinStateSamples=1, calibrationMinBucketSamples=1,
                logisticIterations=5, compareLegacyRoadsidePolicy=True,
            )
            config_path = root / "config.json"
            config_path.write_text(json.dumps(config_data), encoding="utf-8")
            config = load_training_config(config_path)

            report = run_calibrated_experiment(
                input_directory, config_path, root / "report.md", root / "artifacts", config,
            )

            self.assertIn("| offstreet |", report)
            self.assertIn("not_applicable", report)
            self.assertIn("train | 30m", report)
            self.assertIn("roadside_legacy", report)
            self.assertIn("roadside_complete", report)
            self.assertIn("collected_date_taipei_mismatch", report)
            self.assertIn("| offstreet | 1 | 0 | 0 |", report)
            self.assertIn("| roadside | 1 | 0 | 0 |", report)
            self.assertIn("TEST_EVALUATED", report)
            self.assertIn("validation 選定", report)
            self.assertIn("calibrated_state_persistence_v1", report)
            self.assertNotIn("synthetic-lot-private", report)
            self.assertNotIn("synthetic-spot-private", report)
            self.assertNotIn("synthetic-address-private", report)

            artifacts = sorted((root / "artifacts").glob("*.json"))
            self.assertTrue(artifacts)
            payloads = [json.loads(artifact.read_text(encoding="utf-8")) for artifact in artifacts]
            self.assertTrue(all(payload["schemaVersion"] == "parking-logistic-experiment-v2" for payload in payloads))
            self.assertTrue(all(payload["featureNames"] == FEATURE_NAMES for payload in payloads))
            self.assertTrue(all(payload["status"] == "EXPERIMENT_ONLY" for payload in payloads))
            self.assertTrue(all(payload["publishToOnlineSystem"] is False for payload in payloads))

            config_data["baselineExperiment"]["compareLegacyRoadsidePolicy"] = False
            config_path.write_text(json.dumps(config_data), encoding="utf-8")
            config_without_legacy = load_training_config(config_path)
            report_without_legacy = run_calibrated_experiment(
                input_directory, config_path, root / "report-no-legacy.md",
                root / "artifacts-no-legacy", config_without_legacy,
            )
            self.assertNotIn("roadside_legacy", report_without_legacy)
            self.assertIn("roadside_complete", report_without_legacy)

    def test_train_only_circle_and_complete_policy_rejects_partial_arrival(self) -> None:
        data: dict[str, list[RoadsideObservation]] = {"a": [], "b": [], "far": []}
        def add(spot: str, day: str, time: str, *, lat: float = 25.0) -> None:
            stamp = datetime.fromisoformat(f"{day}T{time}:00+00:00")
            local_day = date.fromisoformat(day)
            data[spot].append(RoadsideObservation(
                spot, stamp, local_day, lat, 121.0, True, "ntpc_confirmed_rule",
                "raw_rule", "2", "available", "", False, True,
            ))
        # Train observations freeze a two-spot circle.
        for time in ("00:00", "00:30", "01:00"):
            add("a", "2026-09-24", time)
            add("b", "2026-09-24", time, lat=25.0001)
        # Validation-only remote spot must not enter the frozen candidate circle.
        for time in ("00:00", "00:30", "01:00"):
            add("far", "2026-10-19", time, lat=26.0)
        # Test as-of has both spots; arrival intentionally omits b.
        add("a", "2026-10-22", "00:00")
        add("b", "2026-10-22", "00:00", lat=25.0001)
        add("a", "2026-10-22", "00:30")
        add("a", "2026-10-22", "01:00")

        legacy_stats: dict[tuple[str, int], LabelStats] = {}
        complete_stats: dict[tuple[str, int], LabelStats] = {}
        complete_coverage: dict[tuple[str, int], RoadsideCoverageAudit] = {}
        legacy = build_roadside_samples(data, self.config, self.settings, strict_split_boundaries=True,
                                        coverage_policy_version=COVERAGE_POLICY_LEGACY, admission_audit=legacy_stats)
        complete = build_roadside_samples(data, self.config, self.settings, strict_split_boundaries=True,
                                          coverage_policy_version=COVERAGE_POLICY_COMPLETE,
                                          admission_audit=complete_stats, coverage_audit=complete_coverage)
        self.assertGreater(len(legacy), len(complete))
        self.assertGreater(legacy_stats[("test", 30)].usable, complete_stats[("test", 30)].usable)
        self.assertGreater(complete_stats[("test", 30)].rejected["arrival_coverage_incomplete"], 0)
        self.assertEqual(2, complete_coverage[("test", 30)].expected_spot_observations // complete_coverage[("test", 30)].matched_pairs)
        # Complete observations in the train split are admitted as normal samples.
        self.assertGreater(complete_stats[("train", 30)].usable, 0)


def offstreet_observation(timestamp: str, file_date: str, *, available: int = 3,
                          timezone_explicit: bool = True, file_date_explicit: bool = True) -> OffstreetObservation:
    return OffstreetObservation(
        "lot", datetime.fromisoformat(timestamp.replace("Z", "+00:00")), date.fromisoformat(file_date),
        available, 0.5, False, True, timezone_explicit, file_date_explicit,
    )


def roadside_observation(timestamp: str, file_date: str, *, available: bool = True,
                         timezone_explicit: bool = True, file_date_explicit: bool = True) -> RoadsideObservation:
    return RoadsideObservation(
        "spot", datetime.fromisoformat(timestamp.replace("Z", "+00:00")), date.fromisoformat(file_date),
        25.0, 121.0, available, "confirmed_rule", "confirmed_rule", "2" if available else "1",
        "AVAILABLE" if available else "OCCUPIED", "", False, True,
        timezone_explicit, file_date_explicit,
    )


def write_calibrated_fixture(input_directory: Path) -> None:
    dates = ("2026-09-24", "2026-09-25", "2026-10-19", "2026-10-20",
             "2026-10-21", "2026-10-22", "2026-10-23")
    for day_index, day in enumerate(dates):
        positive = day_index % 2 == 0
        offstreet_rows = []
        roadside_rows = []
        for time_index, local_time in enumerate(("00:00:00", "00:30:00")):
            available = (0 if positive else 5) if time_index == 0 else (5 if positive else 0)
            timestamp = utc_for_taipei(day, local_time)
            offstreet_row = {
                "collected_at_utc": timestamp,
                "collected_date_taipei": day,
                "lot_id": "synthetic-lot-private",
                "available_car": str(available),
                "availability_ratio": str(available / 10),
                "is_unknown": "false",
                "source_api_response_at_utc": timestamp,
                "address": "synthetic-address-private",
            }
            if day == "2026-10-21" and time_index == 0:
                offstreet_row["collected_date_taipei"] = "2026-10-20"
            offstreet_rows.append(offstreet_row)

            for spot_index, spot_id in enumerate(("synthetic-spot-private-a", "synthetic-spot-private-b")):
                status = ("1" if positive else "2") if time_index == 0 else ("2" if positive else "1")
                roadside_row = {
                    "collected_at_utc": timestamp,
                    "collected_date_taipei": day,
                    "spot_id": spot_id,
                    "latitude": "25.0" if spot_index == 0 else "25.0001",
                    "longitude": "121.0",
                    "parking_status_raw": status,
                    "is_available": "",
                    "label_rule": "parkingstatus_0_available_1_unavailable_keep_raw",
                    "source_api_response_at_utc": timestamp,
                    "address": "synthetic-address-private",
                }
                if day == "2026-10-21" and time_index == 1 and spot_index == 1:
                    roadside_row["collected_date_taipei"] = "2026-10-20"
                roadside_rows.append(roadside_row)

        with zipfile.ZipFile(input_directory / f"parking-training-{day}.zip", "w") as archive:
            archive.writestr(f"offstreet_training_samples_{day}.csv", csv_rows(offstreet_rows))
            archive.writestr(f"roadside_training_samples_{day}.csv", csv_rows(roadside_rows))


def utc_for_taipei(day: str, local_time: str) -> str:
    local = datetime.fromisoformat(f"{day}T{local_time}+08:00")
    return local.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def csv_rows(rows: list[dict[str, str]]) -> str:
    from io import StringIO

    output = StringIO()
    writer = csv.DictWriter(output, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


if __name__ == "__main__":
    unittest.main()
