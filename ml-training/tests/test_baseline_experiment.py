from __future__ import annotations

import csv
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import generate_baseline_experiment_report
from parking_ml.training_config import load_training_config


class BaselineExperimentTest(unittest.TestCase):
    def test_reports_offstreet_metrics_and_rejects_roadside(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_zip(root / "parking-training-2026-09-24.zip", "2026-09-24", positive=True)
            write_zip(root / "parking-training-2026-10-01.zip", "2026-10-01", positive=False)
            config = load_training_config(ROOT / "config" / "training-config.example.json")

            report = generate_baseline_experiment_report(root, config)

        self.assertIn("# S3-008 baseline 與第一版模型實驗報告", report)
        self.assertIn("| offstreet | 30 | `EXPERIMENT_ONLY`", report)
        self.assertIn("`train_positive_rate`", report)
        self.assertIn("`logistic_regression_v1`", report)
        self.assertIn("| roadside | 30 | `INSUFFICIENT_DATA`", report)
        self.assertIn("`provisional_roadside_label`", report)
        self.assertNotIn("lot-a,2026", report)


def write_zip(zip_path: Path, day: str, positive: bool) -> None:
    offstreet_rows = [
        offstreet_row(day, "00:00:00", "5" if positive else "0"),
        offstreet_row(day, "00:30:00", "3" if positive else "0"),
        offstreet_row(day, "01:00:00", "0" if positive else "5"),
        offstreet_row(day, "01:30:00", "0" if positive else "3"),
    ]
    roadside_rows = [
        roadside_row("spot-a", day, "00:00:00", "false"),
        roadside_row("spot-a", day, "00:30:00", "true"),
    ]
    with zipfile.ZipFile(zip_path, "w") as archive:
        archive.writestr(f"offstreet_training_samples_{day}.csv", csv_text(offstreet_rows))
        archive.writestr(f"roadside_training_samples_{day}.csv", csv_text(roadside_rows))
        archive.writestr(f"manifest_{day}.json", "{}")


def offstreet_row(day: str, time_text: str, available_car: str) -> dict[str, str]:
    return {
        "collected_at_utc": f"{day}T{time_text}Z",
        "collected_date_taipei": day,
        "weekday_taipei": "4",
        "hour_taipei": "8",
        "minute_bucket_taipei": "0",
        "source": "ntpc",
        "lot_id": "lot-a",
        "lot_name": "A",
        "address": "",
        "total_car": "10",
        "tw97_x": "",
        "tw97_y": "",
        "available_car_raw": available_car,
        "available_car": available_car,
        "availability_ratio": "0.5",
        "is_unknown": "false",
        "label_rule": "available_car_negative_unknown_keep_raw",
        "source_dataset_id": "x",
        "source_dataset_name": "x",
        "source_update_frequency": "3m",
        "source_api_response_at_utc": f"{day}T{time_text}Z",
        "collected_at_taipei": f"{day}T08:00:00+08:00",
    }


def roadside_row(spot_id: str, day: str, time_text: str, is_available: str) -> dict[str, str]:
    return {
        "collected_at_utc": f"{day}T{time_text}Z",
        "collected_date_taipei": day,
        "weekday_taipei": "4",
        "hour_taipei": "8",
        "minute_bucket_taipei": "0",
        "source": "ntpc",
        "area_code": "x",
        "spot_id": spot_id,
        "cell_id": "cell-a",
        "road_id": "road-a",
        "road_name": "",
        "spot_type": "",
        "latitude": "25.0",
        "longitude": "121.0",
        "parking_status_raw": "0",
        "cell_status_raw": "0",
        "is_available": is_available,
        "label_rule": "parkingstatus_0_available_1_unavailable_keep_raw",
        "source_dataset_id": "x",
        "source_dataset_name": "x",
        "source_update_frequency": "2m",
        "source_api_response_at_utc": f"{day}T{time_text}Z",
        "collected_at_taipei": f"{day}T08:00:00+08:00",
    }


def csv_text(rows: list[dict[str, str]]) -> str:
    output = []
    fieldnames = list(rows[0])
    output.append(",".join(fieldnames))
    for row in rows:
        output.append(",".join(row[name] for name in fieldnames))
    return "\n".join(output) + "\n"


if __name__ == "__main__":
    unittest.main()
