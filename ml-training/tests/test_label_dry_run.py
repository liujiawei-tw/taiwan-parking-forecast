from __future__ import annotations

import csv
import sys
import tempfile
import unittest
import zipfile
from datetime import date, datetime, timedelta
from itertools import permutations
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import DATE_POLICY_TIMESTAMP_TAIPEI_V1, build_roadside_samples
from parking_ml.label_dry_run import (
    LabelDryRunSettings,
    RoadsideObservation,
    generate_label_dry_run_report,
    roadside_timestamp_index,
    strict_time_rejection_reason,
)
from parking_ml.training_config import load_training_config


class LabelDryRunTest(unittest.TestCase):
    def test_legacy_roadside_timestamp_index_uses_last_write_wins(self) -> None:
        stamp = datetime.fromisoformat("2026-10-19T00:00:00+00:00")
        unavailable = roadside_observation(stamp, date(2026, 10, 19), False)
        available = roadside_observation(stamp, date(2026, 10, 19), True)

        for rows, expected in (([unavailable, available], True), ([available, unavailable], False)):
            with self.subTest(last_value=expected):
                index, timestamp_dates = roadside_timestamp_index(
                    {"spot-a": rows}, timestamp_authoritative=False,
                )
                self.assertIs(index[stamp]["spot-a"].is_available, expected)
                self.assertEqual(rows[-1].file_date, timestamp_dates[stamp])

    def test_strict_duplicate_index_keeps_untrusted_row_and_reports_stable_reason(self) -> None:
        duplicate_time = datetime.fromisoformat("2026-10-22T00:00:00+00:00")
        trusted = roadside_observation(duplicate_time, date(2026, 10, 22), True)
        date_mismatch = roadside_observation(duplicate_time, date(2026, 10, 21), False)
        timezone_missing = roadside_observation(
            duplicate_time, date(2026, 10, 22), False, timezone_explicit=False,
        )

        config = load_training_config(ROOT / "config" / "training-config-80-10-10.example.json")
        settings = LabelDryRunSettings((30,), timedelta(minutes=15), 500, 1, False)
        train_row = roadside_observation(
            datetime.fromisoformat("2026-09-24T00:00:00+00:00"), date(2026, 9, 24), True,
        )
        arrival_row = roadside_observation(
            datetime.fromisoformat("2026-10-22T00:30:00+00:00"), date(2026, 10, 22), False,
        )

        for rows in permutations((trusted, date_mismatch, timezone_missing)):
            with self.subTest(order=tuple(strict_time_rejection_reason(row) for row in rows)):
                index, timestamp_dates = roadside_timestamp_index(
                    {"spot-a": list(rows)}, timestamp_authoritative=True,
                )
                selected = index[duplicate_time]["spot-a"]
                self.assertEqual("collected_at_timezone_missing", strict_time_rejection_reason(selected))
                self.assertEqual(date(2026, 10, 22), timestamp_dates[duplicate_time])

                audit = {}
                samples = build_roadside_samples(
                    {"spot-a": [train_row, *rows, arrival_row]},
                    config,
                    settings,
                    date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
                    admission_audit=audit,
                )
                self.assertFalse(any(sample.split == "test" for sample in samples))
                self.assertEqual(
                    1,
                    audit[("test", 30)].rejected["collected_at_timezone_missing"],
                )

    def test_unique_roadside_timestamps_keep_policy_dates_and_label_pairing(self) -> None:
        local_midnight = datetime.fromisoformat("2026-10-18T16:00:00+00:00")
        local_arrival = datetime.fromisoformat("2026-10-18T16:30:00+00:00")
        legacy_row = roadside_observation(local_midnight, date(2026, 10, 18), True)
        strict_row = roadside_observation(local_midnight, date(2026, 10, 19), True)

        legacy_index, legacy_dates = roadside_timestamp_index(
            {"spot-a": [legacy_row]}, timestamp_authoritative=False,
        )
        strict_index, strict_dates = roadside_timestamp_index(
            {"spot-a": [strict_row]}, timestamp_authoritative=True,
        )
        self.assertIs(legacy_index[local_midnight]["spot-a"], legacy_row)
        self.assertIs(strict_index[local_midnight]["spot-a"], strict_row)
        self.assertEqual(date(2026, 10, 18), legacy_dates[local_midnight])
        self.assertEqual(date(2026, 10, 19), strict_dates[local_midnight])
        self.assertIsNone(strict_time_rejection_reason(strict_row))

        config = load_training_config(ROOT / "config" / "training-config-80-10-10.example.json")
        settings = LabelDryRunSettings((30,), timedelta(minutes=15), 500, 1, False)
        train_row = roadside_observation(
            datetime.fromisoformat("2026-09-24T00:00:00+00:00"), date(2026, 9, 24), True,
        )
        legacy_arrival = roadside_observation(local_arrival, date(2026, 10, 18), False)
        strict_arrival = roadside_observation(local_arrival, date(2026, 10, 19), False)

        legacy_samples = build_roadside_samples(
            {"spot-a": [train_row, legacy_row, legacy_arrival]}, config, settings,
        )
        strict_samples = build_roadside_samples(
            {"spot-a": [train_row, strict_row, strict_arrival]},
            config,
            settings,
            date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
        )
        legacy_pair = next(sample for sample in legacy_samples if sample.as_of_date is None)
        strict_pair = next(sample for sample in strict_samples if sample.as_of_date == "2026-10-19")
        self.assertEqual("train", legacy_pair.split)
        self.assertEqual("validation", strict_pair.split)
        self.assertFalse(legacy_pair.label)
        self.assertFalse(strict_pair.label)

    def test_generates_summary_without_row_level_labels(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            zip_path = root / "parking-training-2026-09-24.zip"
            write_sample_zip(zip_path)
            config = load_training_config(ROOT / "config" / "training-config.example.json")

            report = generate_label_dry_run_report(root, config)

        self.assertIn("# S3-007 訓練標籤 dry-run 報告", report)
        self.assertIn("| offstreet | train | 30 | 2 | 1", report)
        self.assertIn("| roadside | train | 30 | 2 | 1 | 1 | 1 | 0 |", report)
        self.assertIn("Roadside offline re-label rows：2", report)
        self.assertNotIn("`provisional_roadside_label`", report)
        self.assertNotIn("lot-a,2026", report)


def roadside_observation(
    collected_at: datetime,
    file_date: date,
    is_available: bool,
    *,
    timezone_explicit: bool = True,
    file_date_explicit: bool = True,
) -> RoadsideObservation:
    return RoadsideObservation(
        "spot-a",
        collected_at,
        file_date,
        25.0,
        121.0,
        is_available,
        "confirmed_rule",
        "confirmed_rule",
        "2" if is_available else "1",
        "AVAILABLE" if is_available else "OCCUPIED",
        "",
        False,
        True,
        timezone_explicit,
        file_date_explicit,
    )


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
        roadside_row("spot-a", "2026-09-24T00:00:00Z", "1"),
        roadside_row("spot-a", "2026-09-24T00:30:00Z", "2"),
    ]
    with zipfile.ZipFile(zip_path, "w") as archive:
        archive.writestr("offstreet_training_samples_2026-09-24.csv", csv_text(offstreet_rows))
        archive.writestr("roadside_training_samples_2026-09-24.csv", csv_text(roadside_rows))
        archive.writestr("manifest_2026-09-24.json", "{}")


def roadside_row(spot_id: str, collected_at: str, parking_status_raw: str) -> dict[str, str]:
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
        "parking_status_raw": parking_status_raw,
        "cell_status_raw": "0",
        "is_available": "",
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
