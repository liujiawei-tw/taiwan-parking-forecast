from __future__ import annotations

import math
import sys
import unittest
from unittest.mock import patch
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import (
    COVERAGE_POLICY_COMPLETE,
    COVERAGE_POLICY_LEGACY,
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


if __name__ == "__main__":
    unittest.main()
