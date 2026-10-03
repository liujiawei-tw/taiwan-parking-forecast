#!/usr/bin/env python3
"""Validate NTPC roadside status-code evidence without storing raw rows."""

from __future__ import annotations

import argparse
import csv
import io
import ssl
import sys
import urllib.request
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_OFFICIAL_CSV_URL = (
    "https://data.ntpc.gov.tw/api/datasets/"
    "54a507c4-c038-41b5-bf60-bbecb9d052c6/csv/file"
)


@dataclass
class StatusSummary:
    rows: int = 0
    combos: Counter[tuple[str, str, str]] = field(default_factory=Counter)
    parking_status: Counter[str] = field(default_factory=Counter)
    cell_status: Counter[str] = field(default_factory=Counter)
    isnowcash: Counter[str] = field(default_factory=Counter)
    names_by_combo: dict[tuple[str, str, str], Counter[str]] = field(default_factory=dict)
    memos_by_combo: dict[tuple[str, str, str], Counter[str]] = field(default_factory=dict)

    def add(self, row: dict[str, str]) -> None:
        parking = value(row, "parkingstatus", "parking_status_raw")
        cell = value(row, "cellstatus", "cell_status_raw")
        isnowcash = value(row, "isnowcash") or "missing"
        combo = (parking or "missing", cell or "missing", isnowcash)
        self.rows += 1
        self.combos[combo] += 1
        self.parking_status[combo[0]] += 1
        self.cell_status[combo[1]] += 1
        self.isnowcash[combo[2]] += 1
        if "name" in row:
            self.names_by_combo.setdefault(combo, Counter())[value(row, "name") or "blank"] += 1
        if "memo" in row:
            memo = (value(row, "memo") or "blank")[:80]
            self.memos_by_combo.setdefault(combo, Counter())[memo] += 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize NTPC roadside status-code evidence.")
    parser.add_argument("--official-csv-url", default=DEFAULT_OFFICIAL_CSV_URL)
    parser.add_argument(
        "--allow-insecure-official-csv",
        action="store_true",
        help="Retry official public CSV download without TLS verification when the source certificate fails.",
    )
    parser.add_argument("--area-code", default="65000050", help="Area code to summarize separately.")
    parser.add_argument("--collector-samples", type=Path, help="Optional collector ZIP directory or single ZIP.")
    parser.add_argument("--tdx-spot-samples", type=Path, help="Optional TDX on-street spot historical CSV directory or file.")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    official_rows = read_official_csv(args.official_csv_url, args.allow_insecure_official_csv)
    official_all = StatusSummary()
    official_area = StatusSummary()
    for row in official_rows:
        official_all.add(row)
        if value(row, "areacode") == args.area_code:
            official_area.add(row)

    collector_summary = None
    if args.collector_samples:
        collector_summary = summarize_collector(args.collector_samples)

    tdx_summary = None
    if args.tdx_spot_samples:
        tdx_summary = summarize_tdx_spot_samples(args.tdx_spot_samples)

    report = render_report(
        official_csv_url=args.official_csv_url,
        official_csv_tls_mode="insecure retry allowed" if args.allow_insecure_official_csv else "verified TLS only",
        area_code=args.area_code,
        official_all=official_all,
        official_area=official_area,
        collector_summary=collector_summary,
        tdx_summary=tdx_summary,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(f"Wrote status validation report: {args.output}")
    return 0


def read_official_csv(url: str, allow_insecure: bool) -> list[dict[str, str]]:
    try:
        response = urllib.request.urlopen(url, timeout=30)
    except Exception:
        if not allow_insecure:
            raise
        context = ssl._create_unverified_context()
        response = urllib.request.urlopen(url, timeout=30, context=context)
    with response:
        body = response.read().decode("utf-8-sig")
    return list(csv.DictReader(io.StringIO(body)))


def summarize_collector(input_path: Path) -> StatusSummary:
    summary = StatusSummary()
    for zip_path in iter_zip_paths(input_path):
        with zipfile.ZipFile(zip_path) as archive:
            for name in sorted(archive.namelist()):
                if not name.startswith("roadside_training_samples_") or not name.endswith(".csv"):
                    continue
                with archive.open(name) as raw:
                    text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
                    for row in csv.DictReader(text):
                        summary.add(row)
    return summary


@dataclass
class TdxSpotSummary:
    files: int = 0
    rows: int = 0
    spot_status: Counter[str] = field(default_factory=Counter)
    service_status: Counter[str] = field(default_factory=Counter)
    device_status: Counter[str] = field(default_factory=Counter)
    charge_status: Counter[str] = field(default_factory=Counter)


def summarize_tdx_spot_samples(input_path: Path) -> TdxSpotSummary:
    summary = TdxSpotSummary()
    for csv_path in iter_tdx_csv_paths(input_path):
        summary.files += 1
        with csv_path.open(encoding="utf-8-sig", newline="") as raw:
            for row in csv.DictReader(raw):
                summary.rows += 1
                summary.spot_status[value(row, "SpotStatus") or "missing"] += 1
                summary.service_status[value(row, "ServiceStatus") or "missing"] += 1
                summary.device_status[value(row, "DeviceStatus") or "missing"] += 1
                summary.charge_status[value(row, "ChargeStatus") or "missing"] += 1
    return summary


def iter_zip_paths(input_path: Path) -> list[Path]:
    resolved = input_path.resolve()
    if resolved.is_file() and resolved.suffix.lower() == ".zip":
        return [resolved]
    if resolved.is_dir():
        return sorted(resolved.rglob("parking-training-*.zip"))
    raise FileNotFoundError(f"Collector input path does not exist or is not a ZIP/directory: {resolved}")


def iter_tdx_csv_paths(input_path: Path) -> list[Path]:
    resolved = input_path.resolve()
    if resolved.is_file() and resolved.suffix.lower() == ".csv":
        return [resolved]
    if resolved.is_dir():
        return sorted(path for path in resolved.rglob("*") if path.is_file() and path.suffix.lower() == ".csv")
    raise FileNotFoundError(f"TDX input path does not exist or is not a CSV/directory: {resolved}")


def render_report(
    official_csv_url: str,
    official_csv_tls_mode: str,
    area_code: str,
    official_all: StatusSummary,
    official_area: StatusSummary,
    collector_summary: StatusSummary | None,
    tdx_summary: TdxSpotSummary | None,
) -> str:
    lines = [
        "# S3-010 路邊狀態碼驗證報告",
        "",
        "- 本報告只保存 aggregate 摘要，不保存官方 CSV 原文或 collector row-level 資料。",
        f"- 官方 CSV 來源：`{official_csv_url}`",
        f"- 官方 CSV TLS 讀取模式：{official_csv_tls_mode}",
        f"- 區域摘要 area code：`{area_code}`",
        "",
        "## 官方即時 CSV 摘要",
        "",
        f"- 全檔 rows：{official_all.rows:,}",
        f"- 指定區域 rows：{official_area.rows:,}",
        "",
        "### 全檔狀態組合",
        "",
        combo_table(official_all),
        "",
        "### 指定區域狀態組合",
        "",
        combo_table(official_area),
        "",
        "### 指定區域狀態範例語意",
        "",
        detail_table(official_area),
    ]

    if collector_summary is not None:
        lines.extend(
            [
                "",
                "## Collector 歷史樣本摘要",
                "",
                f"- rows：{collector_summary.rows:,}",
                "",
                combo_table(collector_summary),
            ]
        )

    if tdx_summary is not None:
        lines.extend(
            [
                "",
                "## TDX 路邊格位歷史樣本摘要",
                "",
                f"- CSV files：{tdx_summary.files:,}",
                f"- rows：{tdx_summary.rows:,}",
                "",
                "### SpotStatus",
                "",
                counter_table("SpotStatus", tdx_summary.spot_status),
                "",
                "### ServiceStatus",
                "",
                counter_table("ServiceStatus", tdx_summary.service_status),
                "",
                "### DeviceStatus",
                "",
                counter_table("DeviceStatus", tdx_summary.device_status),
                "",
                "### ChargeStatus",
                "",
                counter_table("ChargeStatus", tdx_summary.charge_status),
            ]
        )

    lines.extend(
        [
            "",
            "## 驗證判定",
            "",
            "- 新北市官方 OpenAPI 與 CSV 全檔可確認欄位與實際值分布，但未直接提供 `parkingstatus`／`cellstatus` enum 字典。",
            "- 交通部停車資料標準的 `SpotStatus` 可作高可信參考：`1=使用中`、`2=空位`、`3=不開放`、`255=未知`。",
            "- 本機 TDX 歷史樣本實際出現 `SpotStatus=1`、`2`、`254`；可支撐 `1/2` 作為 occupied/free 的交叉驗證，但不能直接解釋新北市 Open Data 的 `parkingstatus=5`。",
            "- 新北市前台圖例可作顯示語意佐證：紅色有車、綠色空位、灰色非收費時段、黃色時段性禁停、橙色施工／裝卸貨／身障車格。",
            "- ParkBoss 可作第三方實作交叉驗證，但不得取代官方資料字典或本專案自己的 mapping 驗證。",
            "",
            "## 建議 Mapping",
            "",
            "| parkingstatus | 建議狀態 | 可作訓練 label | 依據 |",
            "| --- | --- | --- | --- |",
            "| `1` | `OCCUPIED` | negative | 交通部 `SpotStatus=1 使用中`；官方與第三方前台皆呈現有車語意。 |",
            "| `2` | `AVAILABLE` | positive | 交通部 `SpotStatus=2 空位`；官方 CSV 中常與 `cellstatus=N` 搭配，需在 collector 改版後驗證。 |",
            "| `3` | `RESTRICTED_OR_CLOSED` | no | 交通部 `SpotStatus=3 不開放`；不應當成一般可停空位。 |",
            "| `5` | `UNKNOWN_OR_SPECIAL` | no | 未在交通部 `SpotStatus` 標準中確認；前台特殊車格／非一般狀態需保守拒絕。 |",
            "| other | `UNKNOWN` | no | 未確認狀態碼。 |",
            "",
            "## 後續",
            "",
            "1. 在 `parking-data-collector` 新增 versioned label rule，不再使用 `parkingstatus=0 -> true`。",
            "2. 用新版 rule 重新產生 roadside daily ZIP 或做離線 re-label dry-run。",
            "3. 回主系統重跑 S3-007／S3-008，確認 roadside positive/negative labels 是否足夠且沒有時間洩漏。",
        ]
    )
    return "\n".join(lines) + "\n"


def combo_table(summary: StatusSummary, limit: int = 12) -> str:
    lines = [
        "| parkingstatus | cellstatus | isnowcash | Count |",
        "| --- | --- | --- | ---: |",
    ]
    for (parking, cell, isnowcash), count in summary.combos.most_common(limit):
        lines.append(f"| `{parking}` | `{cell}` | `{isnowcash}` | {count:,} |")
    return "\n".join(lines)


def detail_table(summary: StatusSummary, limit: int = 10) -> str:
    lines = [
        "| parkingstatus | cellstatus | isnowcash | Count | Top name | Top memo |",
        "| --- | --- | --- | ---: | --- | --- |",
    ]
    for combo, count in summary.combos.most_common(limit):
        top_name = top_value(summary.names_by_combo.get(combo, Counter()))
        top_memo = top_value(summary.memos_by_combo.get(combo, Counter()))
        parking, cell, isnowcash = combo
        lines.append(f"| `{parking}` | `{cell}` | `{isnowcash}` | {count:,} | {top_name} | {top_memo} |")
    return "\n".join(lines)


def counter_table(name: str, counter: Counter[str], limit: int = 12) -> str:
    lines = [
        f"| {name} | Count |",
        "| --- | ---: |",
    ]
    for key, count in counter.most_common(limit):
        lines.append(f"| `{key}` | {count:,} |")
    return "\n".join(lines)


def top_value(counter: Counter[str]) -> str:
    if not counter:
        return "-"
    value_, count = counter.most_common(1)[0]
    return f"{value_} ({count:,})"


def value(row: dict[str, str], *keys: str) -> str:
    for key in keys:
        if key in row and row[key] is not None:
            return str(row[key]).strip()
    return ""


if __name__ == "__main__":
    sys.exit(main())
