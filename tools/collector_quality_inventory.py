#!/usr/bin/env python3
"""Inventory parking-data-collector daily CSV/ZIP exports without copying raw data.

The script prints a Markdown quality report. It intentionally reports counts and
quality summaries only; it does not emit row-level data.
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Iterable


ROADSIDE_HEADER = [
    "collected_at_utc",
    "collected_date_taipei",
    "weekday_taipei",
    "hour_taipei",
    "minute_bucket_taipei",
    "source",
    "area_code",
    "spot_id",
    "cell_id",
    "road_id",
    "road_name",
    "spot_type",
    "latitude",
    "longitude",
    "parking_status_raw",
    "cell_status_raw",
    "is_available",
    "label_rule",
    "source_dataset_id",
    "source_dataset_name",
    "source_update_frequency",
    "source_api_response_at_utc",
    "collected_at_taipei",
]

OFFSTREET_HEADER = [
    "collected_at_utc",
    "collected_date_taipei",
    "weekday_taipei",
    "hour_taipei",
    "minute_bucket_taipei",
    "source",
    "lot_id",
    "lot_name",
    "address",
    "total_car",
    "tw97_x",
    "tw97_y",
    "available_car_raw",
    "available_car",
    "availability_ratio",
    "is_unknown",
    "label_rule",
    "source_dataset_id",
    "source_dataset_name",
    "source_update_frequency",
    "source_api_response_at_utc",
    "collected_at_taipei",
]


@dataclass
class CsvQuality:
    dataset: str
    expected_header: list[str]
    files: list[Path] = field(default_factory=list)
    dates: Counter[str] = field(default_factory=Counter)
    rows: int = 0
    header_mismatches: int = 0
    duplicate_rows: int = 0
    duplicate_observations: int = 0
    missing_collected_at: int = 0
    invalid_collected_at: int = 0
    missing_entity_id: int = 0
    missing_raw_label: int = 0
    missing_label_rule: int = 0
    missing_source_response_at: int = 0
    label_candidate_rows: int = 0
    reliable_label_rows: int = 0
    unknown_or_blank_rows: int = 0
    negative_unknown_rows: int = 0
    invalid_coordinate_rows: int = 0
    provisional_label_rows: int = 0
    parse_errors: list[str] = field(default_factory=list)
    timestamps: set[datetime] = field(default_factory=set)
    manifests: list[Path] = field(default_factory=list)
    manifest_rows: dict[str, int] = field(default_factory=dict)

    def record_file(self, path: Path) -> None:
        self.files.append(path)

    @property
    def coverage_label_ratio(self) -> str:
        if self.rows == 0:
            return "N/A"
        return f"{self.label_candidate_rows / self.rows:.2%}"

    @property
    def reliable_label_ratio(self) -> str:
        if self.rows == 0:
            return "N/A"
        return f"{self.reliable_label_rows / self.rows:.2%}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Collector export root, daily folder, or ZIP file.")
    parser.add_argument("--expected-start", type=parse_date, help="Expected first Asia/Taipei file date.")
    parser.add_argument("--expected-end", type=parse_date, help="Expected last Asia/Taipei file date.")
    parser.add_argument("--output", type=Path, help="Optional Markdown report path.")
    args = parser.parse_args()

    with TemporaryDirectory(prefix="collector-quality-") as temp_dir:
        roots = prepare_roots(args.input, Path(temp_dir))
        roadside = CsvQuality("roadside", ROADSIDE_HEADER)
        offstreet = CsvQuality("offstreet", OFFSTREET_HEADER)

        for root in roots:
            scan_root(root, roadside, offstreet)

        report = render_report(
            source=args.input,
            roadside=roadside,
            offstreet=offstreet,
            expected_start=args.expected_start,
            expected_end=args.expected_end,
        )

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    else:
        print(report)

    return 0


def prepare_roots(input_path: Path, temp_dir: Path) -> list[Path]:
    input_path = input_path.resolve()
    if not input_path.exists():
        raise SystemExit(f"Input path does not exist: {input_path}")
    if input_path.is_file() and input_path.suffix.lower() == ".zip":
        target = temp_dir / input_path.stem
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(input_path) as archive:
            archive.extractall(target)
        return [target]
    if input_path.is_file():
        raise SystemExit(f"Input file must be a .zip export: {input_path}")

    roots = [input_path]
    for zip_path in sorted(input_path.rglob("*.zip")):
        target = temp_dir / zip_path.stem
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(target)
        roots.append(target)
    return roots


def scan_root(root: Path, roadside: CsvQuality, offstreet: CsvQuality) -> None:
    for manifest_path in sorted(root.rglob("manifest_*.json")):
        scan_manifest(manifest_path, roadside, offstreet)
    for csv_path in sorted(root.rglob("*.csv")):
        name = csv_path.name
        if name.startswith("roadside_training_samples_"):
            scan_csv(csv_path, roadside, dataset="roadside")
        elif name.startswith("offstreet_training_samples_"):
            scan_csv(csv_path, offstreet, dataset="offstreet")


def scan_manifest(path: Path, roadside: CsvQuality, offstreet: CsvQuality) -> None:
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        roadside.parse_errors.append(f"{path}: manifest parse error: {exc}")
        return

    for quality in (roadside, offstreet):
        quality.manifests.append(path)

    for key, quality in (("roadside", roadside), ("offstreet", offstreet)):
        section = data.get(key)
        if isinstance(section, dict):
            row_count = section.get("rowCount") or section.get("rows")
            file_name = section.get("fileName") or section.get("file")
            if file_name and isinstance(row_count, int):
                quality.manifest_rows[str(file_name)] = row_count


def scan_csv(path: Path, quality: CsvQuality, dataset: str) -> None:
    quality.record_file(path)
    seen_rows: set[tuple[str, ...]] = set()
    seen_observations: set[tuple[str, str]] = set()
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != quality.expected_header:
                quality.header_mismatches += 1
            for row in reader:
                quality.rows += 1
                row_tuple = tuple(row.get(name, "") for name in reader.fieldnames or [])
                if row_tuple in seen_rows:
                    quality.duplicate_rows += 1
                seen_rows.add(row_tuple)
                scan_common(row, quality)
                if dataset == "roadside":
                    scan_roadside(row, quality, seen_observations)
                else:
                    scan_offstreet(row, quality, seen_observations)
    except Exception as exc:  # noqa: BLE001
        quality.parse_errors.append(f"{path}: CSV parse error: {exc}")


def scan_common(row: dict[str, str], quality: CsvQuality) -> None:
    collected_at = value(row, "collected_at_utc")
    if not collected_at:
        quality.missing_collected_at += 1
    else:
        parsed = parse_datetime(collected_at)
        if parsed is None:
            quality.invalid_collected_at += 1
        else:
            quality.timestamps.add(parsed)
    file_date = value(row, "collected_date_taipei")
    if file_date:
        quality.dates[file_date] += 1
    if not value(row, "label_rule"):
        quality.missing_label_rule += 1
    if not value(row, "source_api_response_at_utc"):
        quality.missing_source_response_at += 1


def scan_roadside(row: dict[str, str], quality: CsvQuality, seen_observations: set[tuple[str, str]]) -> None:
    collected_at = value(row, "collected_at_utc")
    spot_id = value(row, "spot_id")
    if not spot_id:
        quality.missing_entity_id += 1
    else:
        key = (collected_at, spot_id)
        if key in seen_observations:
            quality.duplicate_observations += 1
        seen_observations.add(key)

    if not value(row, "parking_status_raw"):
        quality.missing_raw_label += 1
    is_available = value(row, "is_available")
    label_rule = value(row, "label_rule")
    if is_available in {"true", "false", "TRUE", "FALSE"}:
        quality.label_candidate_rows += 1
    else:
        quality.unknown_or_blank_rows += 1
    if "keep_raw" in label_rule or "parkingstatus_0_available" in label_rule:
        quality.provisional_label_rows += 1
    quality.invalid_coordinate_rows += invalid_coordinate(row, "latitude", "longitude")


def scan_offstreet(row: dict[str, str], quality: CsvQuality, seen_observations: set[tuple[str, str]]) -> None:
    collected_at = value(row, "collected_at_utc")
    lot_id = value(row, "lot_id")
    if not lot_id:
        quality.missing_entity_id += 1
    else:
        key = (collected_at, lot_id)
        if key in seen_observations:
            quality.duplicate_observations += 1
        seen_observations.add(key)

    raw = value(row, "available_car_raw")
    if not raw:
        quality.missing_raw_label += 1
    if value(row, "is_unknown").lower() == "true":
        quality.unknown_or_blank_rows += 1
    else:
        available_car = value(row, "available_car")
        ratio = value(row, "availability_ratio")
        if available_car and ratio:
            quality.label_candidate_rows += 1
            quality.reliable_label_rows += 1
        else:
            quality.unknown_or_blank_rows += 1
    try:
        if raw and int(float(raw)) < 0:
            quality.negative_unknown_rows += 1
    except ValueError:
        quality.unknown_or_blank_rows += 1


def invalid_coordinate(row: dict[str, str], lat_key: str, lon_key: str) -> int:
    try:
        lat = float(value(row, lat_key))
        lon = float(value(row, lon_key))
    except ValueError:
        return 1
    if not (21.0 <= lat <= 26.0 and 119.0 <= lon <= 123.0):
        return 1
    return 0


def render_report(
    source: Path,
    roadside: CsvQuality,
    offstreet: CsvQuality,
    expected_start: date | None,
    expected_end: date | None,
) -> str:
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    missing_dates = expected_missing_dates((roadside, offstreet), expected_start, expected_end)
    decision = next_step_decision(roadside, offstreet)
    lines = [
        "# Collector 訓練資料品質盤點報告",
        "",
        f"- 產生時間：{generated_at}",
        f"- 輸入路徑：`{source}`",
        "- 原始 CSV／ZIP：未複製、未寫入 Git；本報告只列摘要。",
        f"- 建議：{decision}",
        "",
        "## 檔案盤點",
        "",
        "| 類型 | CSV 檔數 | Manifest 檔數 | Row count | 日期數 | Header mismatch | Parse errors |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        file_row(roadside),
        file_row(offstreet),
        "",
        "## 日期與缺日",
        "",
        f"- 路邊日期：{date_summary(roadside)}",
        f"- 路外日期：{date_summary(offstreet)}",
        f"- 期待區間缺日：{', '.join(missing_dates) if missing_dates else '未設定期待區間或未偵測到缺日'}",
        "",
        "## 時間間隔",
        "",
        f"- 路邊 collected_at_utc 間隔：{interval_summary(roadside)}",
        f"- 路外 collected_at_utc 間隔：{interval_summary(offstreet)}",
        "- `collected_at_utc` 是 collector 收集輪次開始時間，不是官方逐筆觀測時間。",
        "- `source_api_response_at_utc` 是官方 API response time 或 fallback，不是每列官方更新時間。",
        "",
        "## Label 可用性與拒絕原因",
        "",
        "| 類型 | Candidate label ratio | Reliable label ratio | Unknown/blank | Negative unknown | Missing raw | Missing entity ID | Duplicate observations | Invalid coordinates | Provisional label rows |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        quality_row(roadside),
        quality_row(offstreet),
        "",
        "## 判定",
        "",
        decision_details(roadside, offstreet),
    ]
    return "\n".join(lines) + "\n"


def file_row(quality: CsvQuality) -> str:
    return (
        f"| {quality.dataset} | {len(quality.files)} | {len(set(quality.manifests))} | {quality.rows} | "
        f"{len(quality.dates)} | {quality.header_mismatches} | {len(quality.parse_errors)} |"
    )


def quality_row(quality: CsvQuality) -> str:
    return (
        f"| {quality.dataset} | {quality.coverage_label_ratio} | {quality.reliable_label_ratio} | "
        f"{quality.unknown_or_blank_rows} | {quality.negative_unknown_rows} | {quality.missing_raw_label} | "
        f"{quality.missing_entity_id} | {quality.duplicate_observations} | {quality.invalid_coordinate_rows} | "
        f"{quality.provisional_label_rows} |"
    )


def date_summary(quality: CsvQuality) -> str:
    if not quality.dates:
        return "未找到 CSV 日期"
    dates = sorted(quality.dates)
    return f"{dates[0]} 至 {dates[-1]}，共 {len(dates)} 日"


def interval_summary(quality: CsvQuality) -> str:
    timestamps = sorted(quality.timestamps)
    if len(timestamps) < 2:
        return "樣本不足"
    minutes = [
        int((later - earlier).total_seconds() // 60)
        for earlier, later in zip(timestamps, timestamps[1:])
    ]
    return (
        f"unique timestamps={len(timestamps)}, min={min(minutes)}m, "
        f"median={statistics.median(minutes)}m, max={max(minutes)}m"
    )


def expected_missing_dates(
    qualities: Iterable[CsvQuality],
    expected_start: date | None,
    expected_end: date | None,
) -> list[str]:
    if expected_start is None or expected_end is None:
        return []
    observed = set()
    for quality in qualities:
        observed.update(quality.dates)
    missing = []
    current = expected_start
    while current <= expected_end:
        text = current.isoformat()
        if text not in observed:
            missing.append(text)
        current = date.fromordinal(current.toordinal() + 1)
    return missing


def next_step_decision(roadside: CsvQuality, offstreet: CsvQuality) -> str:
    if roadside.rows == 0 or offstreet.rows == 0:
        return "暫不進入 #63 label generation；需要先取得 roadside/offstreet daily CSV 或 ZIP 樣本。"
    if roadside.provisional_label_rows > 0:
        return "暫不進入正式模型訓練；roadside label rule 仍是 provisional，需人工確認 raw status 語意。"
    return "可進入 #63 label generation dry run，但仍不得直接訓練或接線上。"


def decision_details(roadside: CsvQuality, offstreet: CsvQuality) -> str:
    if roadside.rows == 0 or offstreet.rows == 0:
        return (
            "- 本輪沒有同時找到 roadside 與 offstreet daily CSV／ZIP，因此只能確認盤點工具與欄位規格，不能宣稱資料足以訓練。\n"
            "- 下一步需從 Google Drive `parking-training-data` 下載至少一組 `parking-training-yyyy-MM-dd.zip`，再重跑本工具。\n"
            "- 在沒有樣本前，#63 應保持未開始；#64 模型訓練更不能啟動。"
        )
    return (
        "- 已取得 daily CSV／ZIP 摘要；仍需人工審查 raw status 語意、缺日、重複與 unknown 比例。\n"
        "- 若 roadside provisional label rows 大於 0，該欄只能作候選標籤，不得當成可靠答案。"
    )


def parse_datetime(text: str) -> datetime | None:
    try:
        normalized = text.replace("Z", "+00:00")
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            return parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except ValueError:
        return None


def parse_date(text: str) -> date:
    return date.fromisoformat(text)


def value(row: dict[str, str], key: str) -> str:
    return (row.get(key) or "").strip()


if __name__ == "__main__":
    sys.exit(main())
