"""Read parking-data-collector CSV rows from daily ZIP exports."""

from __future__ import annotations

import csv
import io
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class CollectorRow:
    dataset: str
    file_date: str
    file_name: str
    values: dict[str, str]


def iter_collector_rows(input_path: Path) -> Iterator[CollectorRow]:
    for zip_path in iter_zip_paths(input_path):
        with zipfile.ZipFile(zip_path) as archive:
            for name in sorted(archive.namelist()):
                dataset = dataset_from_name(name)
                if dataset is None:
                    continue
                file_date = name.rsplit("_", 1)[-1].removesuffix(".csv")
                with archive.open(name) as raw:
                    text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
                    reader = csv.DictReader(text)
                    for row in reader:
                        yield CollectorRow(dataset=dataset, file_date=file_date, file_name=name, values=row)


def iter_zip_paths(input_path: Path) -> list[Path]:
    input_path = input_path.resolve()
    if input_path.is_file() and input_path.suffix.lower() == ".zip":
        return [input_path]
    if input_path.is_dir():
        return sorted(input_path.rglob("parking-training-*.zip"))
    raise FileNotFoundError(f"Collector input path does not exist or is not a ZIP/directory: {input_path}")


def dataset_from_name(name: str) -> str | None:
    file_name = Path(name).name
    if file_name.startswith("roadside_training_samples_") and file_name.endswith(".csv"):
        return "roadside"
    if file_name.startswith("offstreet_training_samples_") and file_name.endswith(".csv"):
        return "offstreet"
    return None
