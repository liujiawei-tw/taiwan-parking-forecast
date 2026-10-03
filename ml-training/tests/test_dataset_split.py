from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.dataset_split import ChronologicalSplit, DateRange
from parking_ml.training_config import load_training_config


class DatasetSplitTest(unittest.TestCase):
    def test_chronological_split_keeps_train_before_test(self) -> None:
        split = ChronologicalSplit(
            train=DateRange(date(2026, 9, 24), date(2026, 9, 30)),
            test=DateRange(date(2026, 10, 1), date(2026, 10, 2)),
        )

        self.assertEqual("train", split.split_for(date(2026, 9, 24)))
        self.assertEqual("train", split.split_for(date(2026, 9, 30)))
        self.assertEqual("test", split.split_for(date(2026, 10, 1)))
        self.assertIsNone(split.split_for(date(2026, 10, 3)))

    def test_split_rejects_overlap(self) -> None:
        with self.assertRaises(ValueError):
            ChronologicalSplit(
                train=DateRange(date(2026, 9, 24), date(2026, 10, 1)),
                test=DateRange(date(2026, 10, 1), date(2026, 10, 2)),
            )

    def test_example_config_is_safe_for_offline_planning(self) -> None:
        config = load_training_config(ROOT / "config" / "training-config.example.json")

        self.assertFalse(config.row_random_split_allowed)
        self.assertFalse(config.publish_to_online_system)
        self.assertEqual(date(2026, 9, 24), config.split.train.start)
        self.assertEqual(date(2026, 10, 2), config.split.test.end)


if __name__ == "__main__":
    unittest.main()
