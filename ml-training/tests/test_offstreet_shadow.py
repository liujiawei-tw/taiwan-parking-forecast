from __future__ import annotations

import csv
import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from io import StringIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ml-training" / "src"))

from parking_ml.offstreet_shadow import generate_predictions, read_jsonl, verify_predictions
from parking_ml.calibrated_experiment import FEATURE_NAMES


class OffstreetShadowTest(unittest.TestCase):
    def test_prospective_features_require_available_fresh_source_response(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            rows = [
                offstreet_row("lot-private-future-response", "2026-10-25", "10:00", 4,
                              source_offset_minutes=8),
                offstreet_row("lot-private-fresh-response", "2026-10-25", "10:00", 5,
                              source_offset_minutes=6),
                offstreet_row("lot-private-stale-at-prediction", "2026-10-25", "09:40", 3,
                              source_offset_minutes=6),
            ]
            missing_source = offstreet_row("lot-private-missing-source", "2026-10-25", "10:00", 2)
            missing_source["source_api_response_at_utc"] = ""
            rows.append(missing_source)
            source = root / "source"
            write_rows_by_day(source, rows)
            predictions = root / "private-predictions.jsonl"
            early_predictions = root / "private-early-predictions.jsonl"

            early = generate_predictions(
                source, config_path, early_predictions,
                mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:02:00+08:00"),
                max_as_of_age_minutes=15,
            )
            self.assertFalse([
                row for row in read_jsonl(early_predictions)
                if row.get("recordType") == "prediction"
            ])
            self.assertEqual(
                early["candidateAuditByLead"]["30"]["rejectedAsOf"].get("source_response_after_prediction_time"),
                2,
            )

            metadata = generate_predictions(
                source, config_path, predictions,
                mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:07:00+08:00"),
                max_as_of_age_minutes=15,
            )

            prediction_rows = [row for row in read_jsonl(predictions) if row.get("recordType") == "prediction"]
            self.assertEqual(len(prediction_rows), 2)
            self.assertTrue(all(row["parkingLotId"] == "lot-private-fresh-response" for row in prediction_rows))
            for prediction in prediction_rows:
                self.assertEqual(prediction["collectedAtUtc"], "2026-10-25T02:00:00Z")
                self.assertEqual(prediction["sourceResponseAtUtc"], "2026-10-25T02:06:00Z")
                self.assertEqual(prediction["predictionAtUtc"], "2026-10-25T02:07:00Z")
                expected_target = "2026-10-25T02:37:00Z" if prediction["leadMinutes"] == 30 else "2026-10-25T03:07:00Z"
                self.assertEqual(prediction["targetArrivalAtUtc"], expected_target)
            self.assertEqual(
                metadata["candidateAuditByLead"]["30"]["rejectedAsOf"].get("source_response_after_prediction_time"),
                1,
            )
            self.assertEqual(
                metadata["candidateAuditByLead"]["30"]["rejectedAsOf"].get("as_of_source_observation_stale_at_prediction"),
                1,
            )
            self.assertEqual(
                metadata["candidateAuditByLead"]["30"]["rejectedAsOf"].get("source_timestamp_missing"),
                1,
            )
            self.assertEqual(metadata["maxAsOfAgeMinutes"], 15)
            self.assertTrue(metadata["isNewHoldout"])

    def test_replay_uses_historical_source_availability_clock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            source = root / "source"
            write_rows_by_day(source, [
                offstreet_row("lot-private-replay", "2026-10-22", "10:00", 4,
                              source_offset_minutes=6),
            ])
            predictions = root / "private-predictions.jsonl"

            metadata = generate_predictions(
                source, config_path, predictions, mode="REPLAY",
                runtime_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )

            prediction_rows = [row for row in read_jsonl(predictions) if row.get("recordType") == "prediction"]
            lead30 = next(row for row in prediction_rows if row["leadMinutes"] == 30)
            self.assertEqual(metadata["evaluationType"], "RETROSPECTIVE_REPLAY")
            self.assertFalse(metadata["isNewHoldout"])
            self.assertEqual(metadata["runtimeAtUtc"], "2026-10-25T04:00:00Z")
            self.assertEqual(lead30["collectedAtUtc"], "2026-10-22T02:00:00Z")
            self.assertEqual(lead30["sourceResponseAtUtc"], "2026-10-22T02:06:00Z")
            self.assertEqual(lead30["predictionAtUtc"], "2026-10-22T02:06:00Z")
            self.assertEqual(lead30["targetArrivalAtUtc"], "2026-10-22T02:36:00Z")

    def test_prospective_mode_does_not_relabel_old_test_zip_as_new_data(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            source = root / "source"
            write_rows_by_day(source, [offstreet_row("lot-private-old", "2026-10-24", "10:00", 4)])
            predictions = root / "private-predictions.jsonl"
            metadata = generate_predictions(
                source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )

            self.assertEqual(metadata["evaluationType"], "PROSPECTIVE")
            self.assertFalse(metadata["isNewHoldout"])
            self.assertEqual(metadata["candidateAuditByLead"]["30"]["candidateCount"], 0)
            report = verify_predictions(
                source, predictions,
                verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            self.assertEqual(report["prospectiveDataStatus"], "NO_QUALIFYING_PROSPECTIVE_DATA")
            self.assertEqual(report["sampledPredictionCount"], 0)

    def test_verification_rejects_target_not_after_prediction_time(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            source = root / "source"
            write_rows_by_day(source, [offstreet_row("lot-private-target", "2026-10-25", "10:00", 4)])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:07:00+08:00"),
            )
            stored = read_jsonl(predictions)
            for row in stored:
                if row.get("recordType") == "prediction":
                    row["targetArrivalAtUtc"] = row["predictionAtUtc"]
            predictions.write_text("".join(json.dumps(row) + "\n" for row in stored), encoding="utf-8")

            results_path = root / "private-results.jsonl"
            verify_predictions(
                source, predictions, results_path=results_path,
                verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            results = [row for row in read_jsonl(results_path) if row.get("recordType") == "prediction"]
            self.assertTrue(all(row["verificationStatus"] == "UNVERIFIABLE" for row in results))
            self.assertTrue(all(
                row["verificationReason"] == "target_arrival_not_after_prediction_time"
                and "label" not in row for row in results
            ))

    def test_arrival_source_response_after_verification_time_stays_pending(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            source = root / "source"
            write_rows_by_day(source, [
                offstreet_row("lot-private-pending", "2026-10-25", "10:00", 4),
                offstreet_row("lot-private-pending", "2026-10-25", "10:30", 3,
                              source_offset_minutes=5),
            ])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            early_results = root / "early-results.jsonl"
            verify_predictions(
                source, predictions, results_path=early_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:31:00+08:00"),
            )
            early = [row for row in read_jsonl(early_results) if row.get("recordType") == "prediction"]
            lead30 = next(row for row in early if row["leadMinutes"] == 30)
            self.assertEqual(lead30["verificationStatus"], "PENDING")
            self.assertEqual(lead30["verificationReason"], "arrival_source_response_not_yet_available")
            self.assertNotIn("label", lead30)

            later_results = root / "later-results.jsonl"
            verify_predictions(
                source, predictions, results_path=later_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:55:00+08:00"),
            )
            later = [row for row in read_jsonl(later_results) if row.get("recordType") == "prediction"]
            lead30 = next(row for row in later if row["leadMinutes"] == 30)
            self.assertEqual(lead30["verificationStatus"], "VERIFIED")
            self.assertTrue(lead30["label"])

    def test_prospective_verification_waits_for_full_arrival_window_and_source_delay(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-finalization"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 4)
            prediction_source = root / "prediction-source"
            write_rows_by_day(prediction_source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                prediction_source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            no_arrival_source = root / "no-arrival-source"
            write_rows_by_day(no_arrival_source, [as_of])
            early_results = root / "early-results.jsonl"
            verify_predictions(
                no_arrival_source, predictions, results_path=early_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:31:00+08:00"),
            )
            early = next(
                row for row in read_jsonl(early_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(early["verificationStatus"], "PENDING")
            self.assertEqual(early["verificationReason"], "arrival_verification_window_open")
            self.assertEqual(early["verificationFinalizationAtUtc"], "2026-10-25T02:55:00Z")
            self.assertNotIn("label", early)

            arrival = offstreet_row(lot_id, "2026-10-25", "10:40", 3, source_offset_minutes=2)
            arrival_source = root / "arrival-source"
            write_rows_by_day(arrival_source, [as_of, arrival])
            before_source_response = root / "before-source-response.jsonl"
            verify_predictions(
                arrival_source, predictions, results_path=before_source_response,
                verified_at=datetime.fromisoformat("2026-10-25T10:41:00+08:00"),
            )
            waiting = next(
                row for row in read_jsonl(before_source_response)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(waiting["verificationStatus"], "PENDING")
            self.assertEqual(waiting["verificationReason"], "arrival_source_response_not_yet_available")
            self.assertNotIn("label", waiting)

            final_results = root / "final-results.jsonl"
            verify_predictions(
                arrival_source, predictions, results_path=final_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:55:00+08:00"),
            )
            final = next(
                row for row in read_jsonl(final_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(final["verificationStatus"], "VERIFIED")
            self.assertTrue(final["label"])
            self.assertEqual(final["arrivalObservedAtUtc"], "2026-10-25T02:40:00Z")

    def test_missing_arrival_stays_pending_until_finalization_then_is_unverifiable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-no-arrival"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 4)
            source = root / "source"
            write_rows_by_day(source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            pending_results = root / "pending-results.jsonl"
            verify_predictions(
                source, predictions, results_path=pending_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:54:00+08:00"),
            )
            pending = next(
                row for row in read_jsonl(pending_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(pending["verificationStatus"], "PENDING")
            self.assertNotIn("label", pending)

            final_results = root / "final-results.jsonl"
            verify_predictions(
                source, predictions, results_path=final_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:55:00+08:00"),
            )
            final = next(
                row for row in read_jsonl(final_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(final["verificationStatus"], "UNVERIFIABLE")
            self.assertEqual(final["verificationReason"], "arrival_observation_missing")
            self.assertNotIn("label", final)

    def test_nearer_arrival_observation_cannot_change_a_finalized_label(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-nearest-arrival"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 4)
            prediction_source = root / "prediction-source"
            write_rows_by_day(prediction_source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                prediction_source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            farther = offstreet_row(lot_id, "2026-10-25", "10:15", 0)
            early_source = root / "early-source"
            write_rows_by_day(early_source, [as_of, farther])
            early_results = root / "early-results.jsonl"
            verify_predictions(
                early_source, predictions, results_path=early_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:54:00+08:00"),
            )
            early = next(
                row for row in read_jsonl(early_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(early["verificationStatus"], "PENDING")
            self.assertNotIn("label", early)

            nearer = offstreet_row(lot_id, "2026-10-25", "10:28", 3)
            complete_source = root / "complete-source"
            write_rows_by_day(complete_source, [as_of, farther, nearer])
            final_results = root / "final-results.jsonl"
            verify_predictions(
                complete_source, predictions, results_path=final_results,
                verified_at=datetime.fromisoformat("2026-10-25T10:55:00+08:00"),
            )
            final = next(
                row for row in read_jsonl(final_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(final["verificationStatus"], "VERIFIED")
            self.assertEqual(final["arrivalObservedAtUtc"], "2026-10-25T02:28:00Z")
            self.assertTrue(final["label"])

            repeated_results = root / "repeated-results.jsonl"
            verify_predictions(
                complete_source, predictions, results_path=repeated_results,
                verified_at=datetime.fromisoformat("2026-10-25T11:00:00+08:00"),
            )
            repeated = next(
                row for row in read_jsonl(repeated_results)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(
                (repeated["verificationStatus"], repeated.get("label"), repeated.get("arrivalObservedAtUtc")),
                (final["verificationStatus"], final.get("label"), final.get("arrivalObservedAtUtc")),
            )

    def test_verification_rejects_as_of_duplicate_conflict_independent_of_row_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-asof-conflict"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 5)
            prediction_source = root / "prediction-source"
            write_rows_by_day(prediction_source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                prediction_source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )
            conflicting = offstreet_row(lot_id, "2026-10-25", "10:00", 2)
            arrival = offstreet_row(lot_id, "2026-10-25", "10:30", 3)
            outcomes = []
            for index, rows in enumerate((
                [as_of, conflicting, arrival],
                [conflicting, as_of, arrival],
            )):
                source = root / f"verification-source-{index}"
                write_rows_by_day(source, rows)
                result_path = root / f"results-{index}.jsonl"
                verify_predictions(
                    source, predictions, results_path=result_path,
                    verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
                )
                result = next(
                    row for row in read_jsonl(result_path)
                    if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
                )
                outcomes.append((
                    result["verificationStatus"], result["verificationReason"], "label" in result,
                ))
            self.assertEqual(outcomes, [
                ("UNVERIFIABLE", "as_of_duplicate_conflict", False),
                ("UNVERIFIABLE", "as_of_duplicate_conflict", False),
            ])

    def test_verification_accepts_an_exact_as_of_duplicate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-asof-identical"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 5)
            prediction_source = root / "prediction-source"
            write_rows_by_day(prediction_source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                prediction_source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            arrival = offstreet_row(lot_id, "2026-10-25", "10:30", 3)
            verification_source = root / "verification-source"
            write_rows_by_day(verification_source, [as_of, dict(as_of), arrival])
            results_path = root / "private-results.jsonl"
            verify_predictions(
                verification_source, predictions, results_path=results_path,
                verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            result = next(
                row for row in read_jsonl(results_path)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(result["verificationStatus"], "VERIFIED")
            self.assertTrue(result["label"])

    def test_verification_rejects_a_missing_or_changed_as_of_fingerprint(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            lot_id = "lot-private-asof-changed"
            as_of = offstreet_row(lot_id, "2026-10-25", "10:00", 5)
            prediction_source = root / "prediction-source"
            write_rows_by_day(prediction_source, [as_of])
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                prediction_source, config_path, predictions, mode="PROSPECTIVE",
                runtime_at=datetime.fromisoformat("2026-10-25T10:00:00+08:00"),
            )

            changed = offstreet_row(lot_id, "2026-10-25", "10:00", 2)
            arrival = offstreet_row(lot_id, "2026-10-25", "10:30", 3)
            verification_source = root / "verification-source"
            write_rows_by_day(verification_source, [changed, arrival])
            results_path = root / "private-results.jsonl"
            verify_predictions(
                verification_source, predictions, results_path=results_path,
                verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            result = next(
                row for row in read_jsonl(results_path)
                if row.get("recordType") == "prediction" and row["leadMinutes"] == 30
            )
            self.assertEqual(result["verificationStatus"], "UNVERIFIABLE")
            self.assertEqual(result["verificationReason"], "as_of_observation_missing_or_changed")
            self.assertNotIn("label", result)

    def test_prediction_phase_does_not_read_or_emit_future_labels(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = root / "training-config.json"
            config = json.loads((ROOT / "ml-training" / "config" / "training-config-80-10-10.example.json").read_text(encoding="utf-8"))
            config["baselineExperiment"]["calibratedPersistenceMinStateSamples"] = 1
            config["labelDryRun"]["leadMinutes"] = [30, 60]
            config_path.write_text(json.dumps(config), encoding="utf-8")

            as_of = offstreet_row("lot-private-a", "2026-10-22", "10:00", 4)
            arrival_positive = offstreet_row("lot-private-a", "2026-10-22", "10:30", 7)
            arrival_negative = offstreet_row("lot-private-a", "2026-10-22", "10:30", 0)
            write_archive(root / "positive", "2026-10-22", [as_of, arrival_positive])
            write_archive(root / "negative", "2026-10-22", [as_of, arrival_negative])
            train_as_of = offstreet_row("lot-private-train", "2026-10-01", "10:00", 8)
            train_arrival = offstreet_row("lot-private-train", "2026-10-01", "10:30", 8)
            write_archive(root / "positive", "2026-10-01", [train_as_of, train_arrival])
            write_archive(root / "negative", "2026-10-01", [train_as_of, train_arrival])

            generated_at = datetime.fromisoformat("2026-10-22T10:15:00+08:00").astimezone(timezone.utc)
            positive_file = root / "positive-predictions.jsonl"
            negative_file = root / "negative-predictions.jsonl"
            generate_predictions(root / "positive", config_path, positive_file, mode="REPLAY", generated_at=generated_at, max_predictions_per_lead=50)
            generate_predictions(root / "negative", config_path, negative_file, mode="REPLAY", generated_at=generated_at, max_predictions_per_lead=50)

            positive_rows = [json.loads(line) for line in positive_file.read_text(encoding="utf-8").splitlines()]
            negative_rows = [json.loads(line) for line in negative_file.read_text(encoding="utf-8").splitlines()]
            positive_predictions = [row for row in positive_rows if row["recordType"] == "prediction"]
            negative_predictions = [row for row in negative_rows if row["recordType"] == "prediction"]
            self.assertEqual(positive_predictions, negative_predictions)
            self.assertTrue(positive_predictions)
            self.assertTrue(all("label" not in row and "arrival" not in row for row in positive_predictions))
            self.assertTrue(all(row["targetArrivalAtUtc"] > generated_at.isoformat() for row in positive_predictions))

    def test_verification_waits_for_target_and_keeps_public_report_aggregate_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            rows = [
                offstreet_row("lot-private-train", "2026-10-01", "10:00", 8),
                offstreet_row("lot-private-train", "2026-10-01", "10:30", 8),
                offstreet_row("lot-private-train", "2026-10-01", "11:00", 8),
                offstreet_row("lot-private-positive", "2026-10-24", "23:30", 4),
                offstreet_row("lot-private-negative", "2026-10-24", "23:00", 4),
                offstreet_row("lot-private-positive", "2026-10-25", "00:02", 3),
                offstreet_row("lot-private-negative", "2026-10-25", "00:00", 0),
            ]
            source = root / "source"
            write_rows_by_day(source, rows)
            predictions = root / "private-predictions.jsonl"
            generated_at = datetime.fromisoformat("2026-10-24T23:45:00+08:00")
            metadata = generate_predictions(source, config_path, predictions, mode="REPLAY", generated_at=generated_at)
            for lead in ("30", "60"):
                self.assertEqual(
                    metadata["modelsByLead"][lead]["logistic_regression_v1"]["reason"],
                    "logistic_artifact_directory_not_configured",
                )

            early = verify_predictions(
                source, predictions, verified_at=datetime.fromisoformat("2026-10-24T23:50:00+08:00")
            )
            # The negative lot's 30m window is also still open at 23:50.
            self.assertEqual(early["pendingCount"], 4)
            self.assertEqual(early["predictionState"]["status"], "EXPERIMENT_ONLY")

            results_path = root / "private-results.jsonl"
            report_json_path = root / "aggregate.json"
            report_md_path = root / "aggregate.md"
            report = verify_predictions(
                source,
                predictions,
                results_path=results_path,
                report_json_path=report_json_path,
                report_markdown_path=report_md_path,
                verified_at=datetime.fromisoformat("2026-10-25T01:00:00+08:00"),
            )
            results = [row for row in read_jsonl(results_path) if row.get("recordType") == "prediction"]
            self.assertEqual(report["byLead"]["30"]["positiveCount"], 1)
            self.assertEqual(report["byLead"]["60"]["negativeCount"], 1)
            self.assertTrue(any(
                row.get("verificationStatus") == "VERIFIED"
                and row.get("arrivalDateTaipei") == "2026-10-25"
                for row in results
            ))
            for lead in ("30", "60"):
                metrics = report["byLead"][lead]["modelMetrics"]
                self.assertEqual(metrics["current_state_persistence"]["samples"], metrics["calibrated_state_persistence_v1"]["samples"])
                self.assertEqual(metrics["logistic_regression_v1"]["status"], "UNAVAILABLE")
            self.assertIsNone(report["byLead"]["30"]["modelMetrics"]["current_state_persistence"]["falsePositiveRate"])
            self.assertFalse(any(row.get("verificationStatus") == "PENDING" for row in results))
            self.assertTrue(any(row.get("verificationStatus") == "UNVERIFIABLE" and row.get("verificationReason") == "arrival_observation_missing" for row in results))
            self.assertFalse(report["predictionState"]["onlinePredictionsWritten"])
            self.assertNotIn("lot-private", report_json_path.read_text(encoding="utf-8"))
            self.assertNotIn("lot-private", report_md_path.read_text(encoding="utf-8"))
            self.assertTrue(any("label" in row for row in results))

    def test_unknown_negative_stale_missing_and_strict_date_observations_are_not_labels(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            rows = [
                offstreet_row("lot-private-train", "2026-10-01", "10:00", 8),
                offstreet_row("lot-private-train", "2026-10-01", "10:30", 8),
                offstreet_row("lot-private-train", "2026-10-01", "11:00", 8),
            ]
            test_cases = [
                ("lot-private-unknown", offstreet_row("lot-private-unknown", "2026-10-22", "10:00", 4),
                 offstreet_row("lot-private-unknown", "2026-10-22", "10:30", 0, unknown=True),
                 "arrival_availability_unknown"),
                ("lot-private-negative", offstreet_row("lot-private-negative", "2026-10-22", "10:00", 4),
                 offstreet_row("lot-private-negative", "2026-10-22", "10:30", -1),
                 "arrival_available_car_negative"),
                ("lot-private-stale", offstreet_row("lot-private-stale", "2026-10-22", "10:00", 4),
                 offstreet_row("lot-private-stale", "2026-10-22", "10:30", 1, source_offset_minutes=-30),
                 "source_observation_stale"),
                ("lot-private-mismatch", offstreet_row("lot-private-mismatch", "2026-10-22", "10:00", 4),
                 offstreet_row("lot-private-mismatch", "2026-10-22", "10:30", 1, file_date_override="2026-10-23"),
                 "collected_date_taipei_mismatch"),
                ("lot-private-missing", offstreet_row("lot-private-missing", "2026-10-22", "10:00", 4),
                 None, "arrival_observation_missing"),
            ]
            for _, as_of, arrival, _ in test_cases:
                rows.append(as_of)
                if arrival:
                    rows.append(arrival)
            bad_as_of = offstreet_row(
                "lot-private-bad-asof", "2026-10-22", "10:00", 4, file_date_override="2026-10-23"
            )
            rows.append(bad_as_of)
            rows.append(offstreet_row("lot-private-bad-asof", "2026-10-22", "10:30", 2))
            bad_source_timezone = offstreet_row(
                "lot-private-bad-source-timezone", "2026-10-22", "10:00", 4,
                source_timezone_explicit=False,
            )
            rows.append(bad_source_timezone)
            rows.append(offstreet_row("lot-private-bad-source-timezone", "2026-10-22", "10:30", 2))

            source = root / "source"
            write_rows_by_day(source, rows)
            predictions = root / "private-predictions.jsonl"
            generate_predictions(
                source, config_path, predictions,
                mode="REPLAY",
                generated_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            prediction_rows = [row for row in read_jsonl(predictions) if row.get("recordType") == "prediction"]
            self.assertFalse(any(
                row["parkingLotId"] == "lot-private-bad-asof"
                and row["asOfAtUtc"] == "2026-10-22T02:00:00Z"
                for row in prediction_rows
            ))

            report = verify_predictions(
                source, predictions, results_path=root / "private-results.jsonl",
                verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            results = [row for row in read_jsonl(root / "private-results.jsonl") if row.get("recordType") == "prediction"]
            lead30 = {row["parkingLotId"]: row for row in results if row["leadMinutes"] == 30}
            for lot_id, _, _, expected_reason in test_cases:
                self.assertEqual(lead30[lot_id]["verificationStatus"], "UNVERIFIABLE")
                self.assertEqual(lead30[lot_id]["verificationReason"], expected_reason)
                self.assertNotIn("label", lead30[lot_id])
            self.assertEqual(
                report["byLead"]["30"]["rejectedAsOfReasons"]["collected_date_taipei_mismatch"], 2
            )
            self.assertEqual(
                report["byLead"]["30"]["rejectedAsOfReasons"]["source_timestamp_timezone_missing"], 1
            )

    def test_qualified_logistic_artifact_uses_the_same_verified_cohort(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_path = write_config(root)
            rows = [
                offstreet_row("lot-private-train", "2026-10-01", "10:00", 8),
                offstreet_row("lot-private-train", "2026-10-01", "10:30", 8),
                offstreet_row("lot-private-train", "2026-10-01", "11:00", 8),
            ]
            for lot_id, available in (("lot-private-positive", 4), ("lot-private-negative", 2)):
                rows.extend([
                    offstreet_row(lot_id, "2026-10-22", "10:00", available),
                    offstreet_row(lot_id, "2026-10-22", "10:30", 3 if available == 4 else 0),
                    offstreet_row(lot_id, "2026-10-22", "11:00", 2 if available == 4 else 0),
                ])
            source = root / "source"
            write_rows_by_day(source, rows)
            artifact_dir = root / "artifacts"
            artifact_dir.mkdir()
            source_hash = hash_zip_files(source)
            config_hash = hashlib.sha256(config_path.read_bytes()).hexdigest()
            ranges = {
                "trainingDateRange": {"start": "2026-09-24", "end": "2026-10-18"},
                "validationDateRange": {"start": "2026-10-19", "end": "2026-10-21"},
                "testDateRange": {"start": "2026-10-22", "end": "2026-10-24"},
            }
            for lead in (30, 60):
                artifact = {
                    "schemaVersion": "parking-logistic-experiment-v2",
                    "featureVersion": "parking_features_as_of_v2",
                    "modelId": f"offstreet-lead-{lead}m-test-artifact",
                    "status": "EXPERIMENT_ONLY",
                    "publishToOnlineSystem": False,
                    "dataset": "offstreet",
                    "coveragePolicyVersion": "OFFSTREET",
                    "target": "arrival_has_confirmed_availability",
                    "leadMinutes": lead,
                    "decisionThreshold": 0.5,
                    "featureNames": FEATURE_NAMES,
                    "weights": [0.0] * len(FEATURE_NAMES),
                    "trainingSamples": 2 if lead == 30 else 1,
                    "sourceZipSha256": source_hash,
                    "trainingConfigSha256": config_hash,
                    **ranges,
                }
                (artifact_dir / f"offstreet-lead-{lead}m-logistic-v2.json").write_text(
                    json.dumps(artifact), encoding="utf-8"
                )

            predictions = root / "private-predictions.jsonl"
            metadata = generate_predictions(
                source, config_path, predictions, logistic_artifact_dir=artifact_dir,
                mode="REPLAY",
                generated_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00"),
            )
            for lead in ("30", "60"):
                self.assertEqual(
                    metadata["modelsByLead"][lead]["logistic_regression_v1"]["status"],
                    "AVAILABLE",
                    {"model": metadata["modelsByLead"][lead]["logistic_regression_v1"], "train": metadata["trainingSamplesByLead"]},
                )
            report = verify_predictions(
                source, predictions, verified_at=datetime.fromisoformat("2026-10-25T12:00:00+08:00")
            )
            for lead in ("30", "60"):
                metrics = report["byLead"][lead]["modelMetrics"]
                self.assertGreater(metrics["current_state_persistence"]["samples"], 0)
                self.assertEqual(metrics["current_state_persistence"]["samples"], metrics["calibrated_state_persistence_v1"]["samples"])
                self.assertEqual(metrics["current_state_persistence"]["samples"], metrics["logistic_regression_v1"]["samples"])


def offstreet_row(
    lot_id: str,
    day: str,
    local_time: str,
    available: int,
    *,
    unknown: bool = False,
    source_offset_minutes: int = 0,
    file_date_override: str | None = None,
    source_timezone_explicit: bool = True,
) -> dict[str, str]:
    local = datetime.fromisoformat(f"{day}T{local_time}+08:00")
    timestamp = local.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    source_timestamp = (local.astimezone(timezone.utc) + timedelta(minutes=source_offset_minutes)).isoformat().replace("+00:00", "Z")
    if not source_timezone_explicit:
        source_timestamp = source_timestamp.removesuffix("Z")
    return {
        "collected_at_utc": timestamp,
        "collected_date_taipei": file_date_override or day,
        "lot_id": lot_id,
        "total_car": "10",
        "available_car": str(available),
        "availability_ratio": str(available / 10),
        "is_unknown": "true" if unknown else "false",
        "source_api_response_at_utc": source_timestamp,
    }


def write_config(root: Path) -> Path:
    config = json.loads((ROOT / "ml-training" / "config" / "training-config-80-10-10.example.json").read_text(encoding="utf-8"))
    config["baselineExperiment"]["calibratedPersistenceMinStateSamples"] = 1
    config["labelDryRun"]["leadMinutes"] = [30, 60]
    path = root / "training-config.json"
    path.write_text(json.dumps(config), encoding="utf-8")
    return path


def write_rows_by_day(directory: Path, rows: list[dict[str, str]]) -> None:
    grouped: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        grouped.setdefault(row["collected_date_taipei"], []).append(row)
    for day, day_rows in grouped.items():
        write_archive(directory, day, day_rows)


def hash_zip_files(directory: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(directory.rglob("parking-training-*.zip")):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def write_archive(directory: Path, day: str, rows: list[dict[str, str]]) -> None:
    directory.mkdir(exist_ok=True)
    output = StringIO()
    writer = csv.DictWriter(output, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)
    with zipfile.ZipFile(directory / f"parking-training-{day}.zip", "a") as archive:
        archive.writestr(f"offstreet_training_samples_{day}.csv", output.getvalue())


if __name__ == "__main__":
    unittest.main()
