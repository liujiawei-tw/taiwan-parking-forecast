"""Label dry-run pairing for offline parking model training."""

from __future__ import annotations

import bisect
import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from parking_ml.collector_reader import CollectorRow, iter_collector_rows
from parking_ml.dataset_split import ChronologicalSplit
from parking_ml.training_config import TrainingConfig


@dataclass(frozen=True)
class LabelDryRunSettings:
    lead_minutes: tuple[int, ...]
    tolerance: timedelta
    roadside_radius_meters: int
    max_roadside_candidate_centers: int
    allow_provisional_roadside_labels: bool


@dataclass(frozen=True)
class OffstreetObservation:
    lot_id: str
    collected_at: datetime
    file_date: date
    available_car: int | None
    availability_ratio: float | None
    is_unknown: bool
    has_source_response: bool


@dataclass(frozen=True)
class RoadsideObservation:
    spot_id: str
    collected_at: datetime
    file_date: date
    latitude: float
    longitude: float
    is_available: bool | None
    label_rule: str
    has_source_response: bool


@dataclass
class LabelStats:
    candidates: int = 0
    usable: int = 0
    positive: int = 0
    negative: int = 0
    rejected: Counter[str] = field(default_factory=Counter)
    entity_ids: set[str] = field(default_factory=set)

    def reject(self, reason: str) -> None:
        self.rejected[reason] += 1


@dataclass
class DryRunReport:
    offstreet: dict[tuple[str, int], LabelStats] = field(default_factory=dict)
    roadside: dict[tuple[str, int], LabelStats] = field(default_factory=dict)
    roadside_centers: int = 0
    roadside_spot_coverage_min: int = 0
    roadside_spot_coverage_max: int = 0
    roadside_spot_coverage_avg: float = 0.0
    source_rows: Counter[str] = field(default_factory=Counter)
    skipped_rows: Counter[str] = field(default_factory=Counter)


def generate_label_dry_run_report(input_path: Path, config: TrainingConfig) -> str:
    settings = settings_from_config(config)
    observations = read_observations(input_path)
    report = DryRunReport(source_rows=observations.source_rows, skipped_rows=observations.skipped_rows)
    report.offstreet = run_offstreet_dry_run(
        observations.offstreet_by_lot,
        split=config.split,
        settings=settings,
    )
    report.roadside, coverage = run_roadside_dry_run(
        observations.roadside_by_spot,
        split=config.split,
        settings=settings,
    )
    report.roadside_centers = len(coverage)
    if coverage:
        report.roadside_spot_coverage_min = min(coverage)
        report.roadside_spot_coverage_max = max(coverage)
        report.roadside_spot_coverage_avg = sum(coverage) / len(coverage)
    return render_report(report, config, settings, input_path)


@dataclass
class Observations:
    offstreet_by_lot: dict[str, list[OffstreetObservation]] = field(default_factory=lambda: defaultdict(list))
    roadside_by_spot: dict[str, list[RoadsideObservation]] = field(default_factory=lambda: defaultdict(list))
    source_rows: Counter[str] = field(default_factory=Counter)
    skipped_rows: Counter[str] = field(default_factory=Counter)


def settings_from_config(config: TrainingConfig) -> LabelDryRunSettings:
    dry_run = config.label_dry_run
    return LabelDryRunSettings(
        lead_minutes=tuple(int(value) for value in dry_run.get("leadMinutes", [30, 60])),
        tolerance=timedelta(minutes=int(dry_run.get("labelToleranceMinutes", 15))),
        roadside_radius_meters=int(dry_run.get("roadsideRadiusMeters", 300)),
        max_roadside_candidate_centers=int(dry_run.get("maxRoadsideCandidateCenters", 50)),
        allow_provisional_roadside_labels=bool(dry_run.get("allowProvisionalRoadsideLabels", False)),
    )


def read_observations(input_path: Path) -> Observations:
    observations = Observations()
    for row in iter_collector_rows(input_path):
        observations.source_rows[row.dataset] += 1
        if row.dataset == "offstreet":
            observation = parse_offstreet(row)
            if observation is None:
                observations.skipped_rows["offstreet_parse_or_missing_key"] += 1
                continue
            observations.offstreet_by_lot[observation.lot_id].append(observation)
        elif row.dataset == "roadside":
            observation = parse_roadside(row)
            if observation is None:
                observations.skipped_rows["roadside_parse_or_missing_key"] += 1
                continue
            observations.roadside_by_spot[observation.spot_id].append(observation)

    for rows in observations.offstreet_by_lot.values():
        rows.sort(key=lambda item: item.collected_at)
    for rows in observations.roadside_by_spot.values():
        rows.sort(key=lambda item: item.collected_at)
    return observations


def parse_offstreet(row: CollectorRow) -> OffstreetObservation | None:
    values = row.values
    lot_id = value(values, "lot_id")
    collected_at = parse_datetime(value(values, "collected_at_utc"))
    file_date = parse_date(value(values, "collected_date_taipei") or row.file_date)
    if not lot_id or collected_at is None or file_date is None:
        return None
    raw_available = value(values, "available_car")
    available_car = parse_int(raw_available) if raw_available else None
    return OffstreetObservation(
        lot_id=lot_id,
        collected_at=collected_at,
        file_date=file_date,
        available_car=available_car,
        availability_ratio=parse_float(value(values, "availability_ratio")),
        is_unknown=value(values, "is_unknown").lower() == "true",
        has_source_response=bool(value(values, "source_api_response_at_utc")),
    )


def parse_roadside(row: CollectorRow) -> RoadsideObservation | None:
    values = row.values
    spot_id = value(values, "spot_id")
    collected_at = parse_datetime(value(values, "collected_at_utc"))
    file_date = parse_date(value(values, "collected_date_taipei") or row.file_date)
    latitude = parse_float(value(values, "latitude"))
    longitude = parse_float(value(values, "longitude"))
    if not spot_id or collected_at is None or file_date is None or latitude is None or longitude is None:
        return None
    return RoadsideObservation(
        spot_id=spot_id,
        collected_at=collected_at,
        file_date=file_date,
        latitude=latitude,
        longitude=longitude,
        is_available=parse_bool(value(values, "is_available")),
        label_rule=value(values, "label_rule"),
        has_source_response=bool(value(values, "source_api_response_at_utc")),
    )


def run_offstreet_dry_run(
    offstreet_by_lot: dict[str, list[OffstreetObservation]],
    split: ChronologicalSplit,
    settings: LabelDryRunSettings,
) -> dict[tuple[str, int], LabelStats]:
    results = make_stats(settings)
    for lot_id, rows in offstreet_by_lot.items():
        timestamps = [row.collected_at for row in rows]
        for as_of in rows:
            split_name = split.split_for(as_of.file_date)
            if split_name is None:
                continue
            for lead in settings.lead_minutes:
                stats = results[(split_name, lead)]
                stats.candidates += 1
                stats.entity_ids.add(lot_id)
                arrival = nearest_observation(rows, timestamps, as_of.collected_at + timedelta(minutes=lead), settings.tolerance)
                if arrival is None:
                    stats.reject("no_arrival_observation")
                    continue
                if arrival.collected_at <= as_of.collected_at:
                    stats.reject("arrival_not_after_as_of")
                    continue
                if not as_of.has_source_response:
                    stats.reject("as_of_missing_source_response")
                    continue
                if arrival.is_unknown or arrival.available_car is None or arrival.available_car < 0:
                    stats.reject("arrival_unknown_or_negative")
                    continue
                stats.usable += 1
                if arrival.available_car > 0:
                    stats.positive += 1
                else:
                    stats.negative += 1
    return results


def run_roadside_dry_run(
    roadside_by_spot: dict[str, list[RoadsideObservation]],
    split: ChronologicalSplit,
    settings: LabelDryRunSettings,
) -> tuple[dict[tuple[str, int], LabelStats], list[int]]:
    results = make_stats(settings)
    latest_by_spot = {spot_id: rows[-1] for spot_id, rows in roadside_by_spot.items() if rows}
    centers = select_candidate_centers(latest_by_spot, settings.max_roadside_candidate_centers)
    circle_spots = [
        spots_within_radius(center, latest_by_spot, settings.roadside_radius_meters)
        for center in centers
    ]
    coverage = [len(spots) for spots in circle_spots]
    by_timestamp, timestamp_dates = roadside_timestamp_index(roadside_by_spot)
    timestamps = sorted(by_timestamp)

    for center_index, spot_ids in enumerate(circle_spots):
        if not spot_ids:
            continue
        entity_id = f"circle-{center_index + 1:03d}"
        as_of_times = timestamps
        for as_of in as_of_times:
            split_name = split.split_for(timestamp_dates[as_of])
            if split_name is None:
                continue
            for lead in settings.lead_minutes:
                stats = results[(split_name, lead)]
                stats.candidates += 1
                stats.entity_ids.add(entity_id)
                arrival_time = nearest_timestamp(timestamps, as_of + timedelta(minutes=lead), settings.tolerance)
                if arrival_time is None:
                    stats.reject("no_arrival_observation")
                    continue
                if arrival_time <= as_of:
                    stats.reject("arrival_not_after_as_of")
                    continue
                arrival_rows = [
                    by_timestamp[arrival_time][spot_id]
                    for spot_id in spot_ids
                    if spot_id in by_timestamp[arrival_time]
                ]
                if not arrival_rows:
                    stats.reject("no_spots_observed_in_circle_at_arrival")
                    continue
                as_of_rows = [
                    by_timestamp[as_of][spot_id]
                    for spot_id in spot_ids
                    if spot_id in by_timestamp[as_of]
                ]
                if any(not row.has_source_response for row in as_of_rows):
                    stats.reject("as_of_missing_source_response")
                    continue
                if not settings.allow_provisional_roadside_labels and any(is_provisional(row) for row in arrival_rows):
                    stats.reject("provisional_roadside_label")
                    continue
                labels = [row.is_available for row in arrival_rows]
                if any(label is None for label in labels):
                    stats.reject("arrival_unknown_label")
                    continue
                stats.usable += 1
                if any(labels):
                    stats.positive += 1
                else:
                    stats.negative += 1
    return results, coverage


def make_stats(settings: LabelDryRunSettings) -> dict[tuple[str, int], LabelStats]:
    return {
        (split_name, lead): LabelStats()
        for split_name in ("train", "test")
        for lead in settings.lead_minutes
    }


def nearest_observation(
    rows: list[OffstreetObservation],
    timestamps: list[datetime],
    target: datetime,
    tolerance: timedelta,
) -> OffstreetObservation | None:
    index = bisect.bisect_left(timestamps, target)
    candidates = []
    if index < len(rows):
        candidates.append(rows[index])
    if index > 0:
        candidates.append(rows[index - 1])
    if not candidates:
        return None
    nearest = min(candidates, key=lambda row: abs(row.collected_at - target))
    if abs(nearest.collected_at - target) <= tolerance:
        return nearest
    return None


def nearest_timestamp(timestamps: list[datetime], target: datetime, tolerance: timedelta) -> datetime | None:
    index = bisect.bisect_left(timestamps, target)
    candidates = []
    if index < len(timestamps):
        candidates.append(timestamps[index])
    if index > 0:
        candidates.append(timestamps[index - 1])
    if not candidates:
        return None
    nearest = min(candidates, key=lambda value_: abs(value_ - target))
    if abs(nearest - target) <= tolerance:
        return nearest
    return None


def select_candidate_centers(
    latest_by_spot: dict[str, RoadsideObservation],
    limit: int,
) -> list[RoadsideObservation]:
    return [latest_by_spot[spot_id] for spot_id in sorted(latest_by_spot)[:limit]]


def spots_within_radius(
    center: RoadsideObservation,
    latest_by_spot: dict[str, RoadsideObservation],
    radius_meters: int,
) -> list[str]:
    return [
        spot_id
        for spot_id, row in latest_by_spot.items()
        if haversine_meters(center.latitude, center.longitude, row.latitude, row.longitude) <= radius_meters
    ]


def roadside_timestamp_index(
    roadside_by_spot: dict[str, list[RoadsideObservation]],
) -> tuple[dict[datetime, dict[str, RoadsideObservation]], dict[datetime, date]]:
    index: dict[datetime, dict[str, RoadsideObservation]] = defaultdict(dict)
    timestamp_dates: dict[datetime, date] = {}
    for spot_id, rows in roadside_by_spot.items():
        for row in rows:
            index[row.collected_at][spot_id] = row
            timestamp_dates[row.collected_at] = row.file_date
    return index, timestamp_dates


def is_provisional(row: RoadsideObservation) -> bool:
    return "keep_raw" in row.label_rule or "parkingstatus_0_available" in row.label_rule


def render_report(
    report: DryRunReport,
    config: TrainingConfig,
    settings: LabelDryRunSettings,
    input_path: Path,
) -> str:
    lines = [
        "# S3-007 訓練標籤 dry-run 報告",
        "",
        f"- 輸入路徑：`{input_path}`",
        "- 原始 CSV／ZIP：未複製、未寫入 Git；本報告只列摘要。",
        f"- Lead times：{', '.join(str(value) + 'm' for value in settings.lead_minutes)}",
        f"- Label window：arrivalAt ± {int(settings.tolerance.total_seconds() // 60)}m",
        f"- Roadside radius：{settings.roadside_radius_meters}m",
        f"- Roadside candidate centers：{report.roadside_centers}",
        f"- Roadside provisional labels allowed：{str(settings.allow_provisional_roadside_labels).lower()}",
        "",
        "## Label Definition",
        "",
        "- `asOf` 是模型做出預測的時間；特徵只能使用 `asOf` 當下與以前的觀測。",
        "- `arrivalAt = asOf + leadTime`；`arrivalAt` 附近時間窗內的觀測只能作 label，不可回填成特徵。",
        "- 路邊 label 使用 300 公尺候選目的地圓；抵達時間窗內任一格 `is_available=true` 才是正例。",
        "- 路外 label 使用同一 `lot_id`；抵達時間窗內 `available_car > 0` 才是正例，unknown、負值或缺觀測皆拒絕。",
        "",
        "## Source Rows",
        "",
        "| Dataset | Rows | Skipped rows |",
        "| --- | ---: | ---: |",
        f"| roadside | {report.source_rows['roadside']} | {report.skipped_rows['roadside_parse_or_missing_key']} |",
        f"| offstreet | {report.source_rows['offstreet']} | {report.skipped_rows['offstreet_parse_or_missing_key']} |",
        "",
        "## Roadside 300m Candidate Coverage",
        "",
        "| Candidate centers | Min spots | Avg spots | Max spots |",
        "| ---: | ---: | ---: | ---: |",
        (
            f"| {report.roadside_centers} | {report.roadside_spot_coverage_min} | "
            f"{report.roadside_spot_coverage_avg:.1f} | {report.roadside_spot_coverage_max} |"
        ),
        "",
        "## Dry-run Summary",
        "",
        "| Dataset | Split | Lead | Candidate samples | Usable labels | Rejected | Positive | Negative | Entity coverage |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for dataset, stats_by_key in (("roadside", report.roadside), ("offstreet", report.offstreet)):
        for split_name in ("train", "test"):
            for lead in settings.lead_minutes:
                stats = stats_by_key[(split_name, lead)]
                lines.append(summary_row(dataset, split_name, lead, stats))

    lines.extend(
        [
            "",
            "## Rejection Reasons",
            "",
            "| Dataset | Split | Lead | Reason | Count |",
            "| --- | --- | ---: | --- | ---: |",
        ]
    )
    for dataset, stats_by_key in (("roadside", report.roadside), ("offstreet", report.offstreet)):
        for split_name in ("train", "test"):
            for lead in settings.lead_minutes:
                stats = stats_by_key[(split_name, lead)]
                if not stats.rejected:
                    lines.append(f"| {dataset} | {split_name} | {lead} | none | 0 |")
                for reason, count in stats.rejected.most_common():
                    lines.append(f"| {dataset} | {split_name} | {lead} | `{reason}` | {count} |")

    lines.extend(
        [
            "",
            "## Split",
            "",
            f"- Train：{config.split.train.start.isoformat()}..{config.split.train.end.isoformat()}",
            f"- Test：{config.split.test.start.isoformat()}..{config.split.test.end.isoformat()}",
            "- 不使用 row-random split，以避免同日或同輪收集資料洩漏。",
            "",
            "## 判定",
            "",
            "- 本 dry-run 可證明時間配對規則：feature side 只使用 `asOf`，label side 才讀 `arrivalAt` 附近資料。",
            "- Roadside 預設拒絕 provisional label，因此目前仍不能產生可訓練的路邊可靠標籤。",
            "- Offstreet 可產生可靠 label 候選，但仍需在 #64 與 baseline／模型門檻一起審查，不得直接接線上機率。",
        ]
    )
    return "\n".join(lines) + "\n"


def summary_row(dataset: str, split_name: str, lead: int, stats: LabelStats) -> str:
    rejected = sum(stats.rejected.values())
    return (
        f"| {dataset} | {split_name} | {lead} | {stats.candidates} | {stats.usable} | "
        f"{rejected} | {stats.positive} | {stats.negative} | {len(stats.entity_ids)} |"
    )


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371000
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    return 2 * radius * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def parse_datetime(text: str) -> datetime | None:
    if not text:
        return None
    try:
        normalized = text.replace("Z", "+00:00")
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except ValueError:
        return None


def parse_date(text: str) -> date | None:
    if not text:
        return None
    try:
        return date.fromisoformat(text)
    except ValueError:
        return None


def parse_int(text: str) -> int | None:
    try:
        return int(float(text))
    except ValueError:
        return None


def parse_float(text: str) -> float | None:
    try:
        return float(text)
    except ValueError:
        return None


def parse_bool(text: str) -> bool | None:
    lowered = text.lower()
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    return None


def value(row: dict[str, str], key: str) -> str:
    return (row.get(key) or "").strip()
