import sys
import json
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.experiment_audit import audit_observations, overlap_summary, pairing_summary, series_summary
from parking_ml.label_dry_run import read_observations
from parking_ml.training_config import load_training_config
from test_baseline_experiment import write_zip


class ExperimentAuditTest(unittest.TestCase):
    def test_audit_reports_real_fixture_pair_counts_without_private_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            write_zip(path / "parking-training-2026-09-24.zip", "2026-09-24", True)
            write_zip(path / "parking-training-2026-10-01.zip", "2026-10-01", False)
            config = load_training_config(ROOT / "config/training-config.example.json")
            report = audit_observations(read_observations(path), config)
        self.assertGreater(report["roadside_raw_series"]["changes"], 0)
        for dataset in ("roadside", "offstreet"):
            for lead in (30, 60):
                count = sum(report["samples"][f"{dataset}_{split}_{lead}"]["samples"]
                            for split in ("train", "test"))
                self.assertEqual(report["pairing"][f"{dataset}_{lead}"]["usable_pairs"], count)
        self.assertNotIn("spot-a", json.dumps(report))
        self.assertNotIn("lot-a", json.dumps(report))

    def test_constant_entities_do_not_count_as_independent_changes(self):
        report = series_summary([[True, True, True], [False, True, False], []])
        self.assertEqual(report["constant_entities"], 1)
        self.assertEqual(report["changes"], 2)
        self.assertEqual(report["adjacent_comparisons"], 4)
        self.assertEqual(report["change_rate"], 0.5)

    def test_overlap_distinguishes_identical_and_shared_circles(self):
        report = overlap_summary([{1, 2}, {1, 2}, {2, 3}, {4}])
        self.assertEqual(report["identical_pairs"], 1)
        self.assertEqual(report["overlapping_pairs"], 3)
        self.assertEqual(report["shared_spots"], 2)
        self.assertEqual(report["spot_memberships"], 7)

    def test_train_labels_crossing_holdout_are_reported(self):
        config = load_training_config(ROOT / "config/training-config.example.json")
        before = datetime(2026, 9, 30, 15, 45, tzinfo=timezone.utc)
        after = before + timedelta(minutes=30)
        report = pairing_summary([
            (before, date(2026, 9, 30), after, date(2026, 10, 1), after),
            (before, date(2026, 9, 30), before, date(2026, 9, 30), after),
        ], config.split)
        self.assertEqual(report["train_label_in_test"], 1)
        self.assertEqual(report["arrival_not_after_feature"], 1)
        self.assertEqual(report["arrival_offset_minutes_min"], -30)


if __name__ == "__main__":
    unittest.main()
