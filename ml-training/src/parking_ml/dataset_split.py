"""Chronological dataset split helpers.

The training pipeline intentionally avoids row-random splitting because parking
observations collected in the same day or collection window are highly related.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta


@dataclass(frozen=True)
class DateRange:
    start: date
    end: date

    def __post_init__(self) -> None:
        if self.start > self.end:
            raise ValueError("DateRange start must be earlier than or equal to end")

    def contains(self, value: date) -> bool:
        return self.start <= value <= self.end

    def dates(self) -> list[date]:
        current = self.start
        values: list[date] = []
        while current <= self.end:
            values.append(current)
            current += timedelta(days=1)
        return values

    def overlaps(self, other: "DateRange") -> bool:
        return self.start <= other.end and other.start <= self.end


@dataclass(frozen=True)
class ChronologicalSplit:
    train: DateRange
    test: DateRange

    def __post_init__(self) -> None:
        if self.train.overlaps(self.test):
            raise ValueError("Training and test date ranges must not overlap")
        if self.train.end >= self.test.start:
            raise ValueError("Training range must end before test range starts")

    def split_for(self, value: date) -> str | None:
        if self.train.contains(value):
            return "train"
        if self.test.contains(value):
            return "test"
        return None
