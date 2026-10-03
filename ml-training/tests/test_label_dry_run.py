from __future__ import annotations

import csv
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.label_dry_run import generate_label_dry_run_report
from parking_ml.training_config import load_training_config


class LabelDryRunTest(unittest.TestCase):
    def test_generates_summary_without_row_level_labels(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            zip_path = root / "parking-training-2026-09-24.zip"
            write_sample_zip(zip_path)
            config = load_training_config(ROOT / "config" / "training-config.example.json")

            report = generate_label_dry_run_report(root, config)

        self.assertIn("# S3-007 訓練標籤 dry-run 報告", report)
        self.assertIn("| offstreet | train | 30 | 2 | 1", report)
        self.assertIn("roadside | train | 30", report)
        self.assertIn("`provisional_roadside_label`", report)
        self.assertNotIn("lot-a,2026", report)


def write_sample_zip(zip_path: Path) -> None:
    offstreet_rows = [
        {
            "collected_at_utc": "2026-09-24T00:00:00Z",
            "collected_date_taipei": "2026-09-24",
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
            "available_car_raw": "5",
            "available_car": "5",
            "availability_ratio": "0.5",
            "is_unknown": "false",
            "label_rule": "available_car_negative_unknown_keep_raw",
            "source_dataset_id": "x",
            "source_dataset_name": "x",
            "source_update_frequency": "3m",
            "source_api_response_at_utc": "2026-09-24T00:00:00Z",
            "collected_at_taipei": "2026-09-24T08:00:00+08:00",
        },
        {
            "collected_at_utc": "2026-09-24T00:30:00Z",
            "collected_date_taipei": "2026-09-24",
            "weekday_taipei": "4",
            "hour_taipei": "8",
            "minute_bucket_taipei": "30",
            "source": "ntpc",
            "lot_id": "lot-a",
            "lot_name": "A",
            "address": "",
            "total_car": "10",
            "tw97_x": "",
            "tw97_y": "",
            "available_car_raw": "2",
            "available_car": "2",
            "availability_ratio": "0.2",
            "is_unknown": "false",
            "label_rule": "available_car_negative_unknown_keep_raw",
            "source_dataset_id": "x",
            "source_dataset_name": "x",
            "source_update_frequency": "3m",
            "source_api_response_at_utc": "2026-09-24T00:30:00Z",
            "collected_at_taipei": "2026-09-24T08:30:00+08:00",
        },
    ]
    roadside_rows = [
        roadside_row("spot-a", "2026-09-24T00:00:00Z", "false"),
        roadside_row("spot-a", "2026-09-24T00:30:00Z", "true"),
    ]
    with zipfile.ZipFile(zip_path, "w") as archive:
        archive.writestr("offstreet_training_samples_2026-09-24.csv", csv_text(offstreet_rows))
        archive.writestr("roadside_training_samples_2026-09-24.csv", csv_text(roadside_rows))
        archive.writestr("manifest_2026-09-24.json", "{}")


def roadside_row(spot_id: str, collected_at: str, is_available: str) -> dict[str, str]:
    return {
        "collected_at_utc": collected_at,
        "collected_date_taipei": "2026-09-24",
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
        "source_api_response_at_utc": collected_at,
        "collected_at_taipei": "2026-09-24T08:00:00+08:00",
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
