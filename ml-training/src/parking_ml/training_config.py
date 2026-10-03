"""Training configuration loader for offline model experiments."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from parking_ml.dataset_split import ChronologicalSplit, DateRange


@dataclass(frozen=True)
class TrainingConfig:
    schema_version: str
    source_type: str
    local_sample_directory: str
    split: ChronologicalSplit
    row_random_split_allowed: bool
    publish_to_online_system: bool
    excluded_dates: tuple[str, ...]
    label_dry_run: dict[str, Any]
    baseline_experiment: dict[str, Any]

    def render_plan(self) -> str:
        excluded = ", ".join(self.excluded_dates) if self.excluded_dates else "none"
        return "\n".join(
            [
                "# Offline Training Plan",
                "",
                f"- Schema version: {self.schema_version}",
                f"- Source type: {self.source_type}",
                f"- Local samples: {self.local_sample_directory}",
                f"- Excluded dates: {excluded}",
                f"- Train dates: {self.split.train.start.isoformat()}..{self.split.train.end.isoformat()}",
                f"- Test dates: {self.split.test.start.isoformat()}..{self.split.test.end.isoformat()}",
                f"- Row-random split allowed: {str(self.row_random_split_allowed).lower()}",
                f"- Publish to online system: {str(self.publish_to_online_system).lower()}",
                "",
                "This command only prints the plan; it does not train or publish a model.",
            ]
        )


def load_training_config(path: Path) -> TrainingConfig:
    data = json.loads(path.read_text(encoding="utf-8"))
    split = data["split"]
    outputs = data["outputs"]
    source = data["source"]
    excluded_dates = tuple(item["date"] for item in source.get("excludedDates", []))
    config = TrainingConfig(
        schema_version=str(data["schemaVersion"]),
        source_type=str(source["type"]),
        local_sample_directory=str(source["localSampleDirectory"]),
        split=ChronologicalSplit(
            train=DateRange(parse_date(split["trainStart"]), parse_date(split["trainEnd"])),
            test=DateRange(parse_date(split["testStart"]), parse_date(split["testEnd"])),
        ),
        row_random_split_allowed=bool(split["rowRandomSplitAllowed"]),
        publish_to_online_system=bool(outputs["publishToOnlineSystem"]),
        excluded_dates=excluded_dates,
        label_dry_run=dict(data.get("labelDryRun", {})),
        baseline_experiment=dict(data.get("baselineExperiment", {})),
    )
    validate_config(config)
    return config


def parse_date(value: Any) -> date:
    if not isinstance(value, str):
        raise TypeError("Expected ISO date string")
    return date.fromisoformat(value)


def validate_config(config: TrainingConfig) -> None:
    if config.row_random_split_allowed:
        raise ValueError("Row-random split is not allowed for time-series training data")
    if config.publish_to_online_system:
        raise ValueError("Training config must not publish directly to the online system")
