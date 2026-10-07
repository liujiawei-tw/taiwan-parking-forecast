"""Aggregate diagnostics for the frozen S3-008 experiment, without fitting models."""

from collections import Counter, defaultdict
from datetime import timedelta
from itertools import combinations
from statistics import median

from parking_ml.baseline_experiment import (
    build_offstreet_samples, build_roadside_samples, reliable_roadside_rows,
)
from parking_ml.label_dry_run import (
    nearest_observation, nearest_timestamp, roadside_timestamp_index,
    select_candidate_centers, settings_from_config, spots_within_radius,
)


def series_summary(series):
    comparisons = changes = constant = 0
    lengths = []
    for values in series:
        lengths.append(len(values))
        differences = sum(left != right for left, right in zip(values, values[1:]))
        comparisons += max(0, len(values) - 1)
        changes += differences
        constant += bool(values) and not differences
    return {
        "entities": len(lengths), "constant_entities": constant,
        "adjacent_comparisons": comparisons, "changes": changes,
        "change_rate": changes / comparisons if comparisons else None,
        "observations_min": min(lengths, default=0),
        "observations_max": max(lengths, default=0),
    }


def sample_summary(samples):
    groups = defaultdict(list)
    for sample in samples:
        groups[sample.entity_id].append(sample.label)
    summary = series_summary(list(groups.values()))
    summary.update({
        "samples": len(samples), "positive": sum(sample.label for sample in samples),
        "current_label_disagreements": sum(
            sample.current_available is not None and sample.current_available != sample.label
            for sample in samples
        ),
        "constant_positive_entities": sum(bool(v) and all(v) for v in groups.values()),
        "constant_negative_entities": sum(bool(v) and not any(v) for v in groups.values()),
    })
    return summary


def overlap_summary(circles):
    pairs = list(combinations(circles, 2))
    jaccards = [len(a & b) / len(a | b) if a | b else 0 for a, b in pairs]
    memberships = Counter(spot for circle in circles for spot in circle)
    return {
        "circles": len(circles), "pair_count": len(pairs),
        "overlapping_pairs": sum(bool(a & b) for a, b in pairs),
        "identical_pairs": sum(a == b for a, b in pairs),
        "jaccard_mean": sum(jaccards) / len(jaccards) if jaccards else None,
        "jaccard_max": max(jaccards, default=0),
        "unique_spots": len(memberships),
        "spot_memberships": sum(memberships.values()),
        "shared_spots": sum(count > 1 for count in memberships.values()),
        "max_circles_per_spot": max(memberships.values(), default=0),
    }


def pairing_summary(pairs, split):
    result = Counter()
    errors = []
    for as_of, as_of_date, arrival, arrival_date, target in pairs:
        name = split.split_for(as_of_date)
        result["usable_pairs"] += 1
        result["arrival_not_after_feature"] += arrival <= as_of
        result["label_crosses_split"] += name != split.split_for(arrival_date)
        result["train_label_in_test"] += name == "train" and split.split_for(arrival_date) == "test"
        errors.append((arrival - target).total_seconds() / 60)
    return {**{key: result[key] for key in (
        "usable_pairs", "arrival_not_after_feature", "label_crosses_split", "train_label_in_test"
    )}, "arrival_offset_minutes_min": min(errors, default=None),
        "arrival_offset_minutes_max": max(errors, default=None)}


def audit_observations(observations, config):
    settings = settings_from_config(config)
    roadside = observations.roadside_by_spot
    offstreet = observations.offstreet_by_lot
    latest = {key: rows[-1] for key, rows in roadside.items() if rows}
    circles = [set(spots_within_radius(center, latest, settings.roadside_radius_meters))
               for center in select_candidate_centers(latest, settings.max_roadside_candidate_centers)]
    by_time, dates = roadside_timestamp_index(roadside)
    timestamps = sorted(by_time)
    gaps = [(right - left).total_seconds() / 60 for left, right in zip(timestamps, timestamps[1:])]
    # Snapshot signatures remain in memory; no IDs or row-level values are exported.
    signatures = {tuple(sorted((key, row.parking_status_raw) for key, row in rows.items()))
                  for rows in by_time.values()}
    report = {
        "source_rows": dict(observations.source_rows),
        "roadside_raw_series": series_summary([
            [row.parking_status_raw for row in rows] for rows in roadside.values()]),
        "roadside_binary_series": series_summary([
            [row.is_available for row in rows] for rows in roadside.values()]),
        "offstreet_count_series": series_summary([
            [(row.available_car, row.is_unknown) for row in rows] for rows in offstreet.values()]),
        "roadside_collection_times": len(timestamps),
        "roadside_collection_gap_minutes": {
            "min": min(gaps, default=None), "median": median(gaps) if gaps else None,
            "max": max(gaps, default=None),
        },
        "roadside_missing_source_response_rows": sum(
            not row.has_source_response for rows in roadside.values() for row in rows),
        "roadside_distinct_raw_snapshots": len(signatures),
        "roadside_spots_with_coordinate_changes": sum(
            len({(row.latitude, row.longitude) for row in rows}) > 1 for rows in roadside.values()),
        "circle_overlap": overlap_summary(circles),
        "samples": {}, "pairing": {},
    }
    for dataset, samples in (
        ("roadside", build_roadside_samples(roadside, config, settings)),
        ("offstreet", build_offstreet_samples(offstreet, config, settings)),
    ):
        for lead in settings.lead_minutes:
            for split_name in ("train", "test"):
                selected = [s for s in samples if s.lead_minutes == lead and s.split == split_name]
                report["samples"][f"{dataset}_{split_name}_{lead}"] = sample_summary(selected)
    for lead in settings.lead_minutes:
        roadside_pairs = []
        for circle in circles:
            for as_of in timestamps:
                if config.split.split_for(dates[as_of]) is None:
                    continue
                current = [by_time[as_of][spot] for spot in circle if spot in by_time[as_of]]
                target = as_of + timedelta(minutes=lead)
                arrival = nearest_timestamp(timestamps, target, settings.tolerance)
                if arrival is None or arrival <= as_of:
                    continue
                future = [by_time[arrival][spot] for spot in circle if spot in by_time[arrival]]
                if reliable_roadside_rows(current, settings) and reliable_roadside_rows(future, settings):
                    roadside_pairs.append((as_of, dates[as_of], arrival, dates[arrival], target))
        offstreet_pairs = []
        for rows in offstreet.values():
            times = [row.collected_at for row in rows]
            for row in rows:
                if config.split.split_for(row.file_date) is None or not row.has_source_response:
                    continue
                target = row.collected_at + timedelta(minutes=lead)
                arrival = nearest_observation(rows, times, target, settings.tolerance)
                if arrival is None or arrival.collected_at <= row.collected_at:
                    continue
                if arrival.is_unknown or arrival.available_car is None or arrival.available_car < 0:
                    continue
                offstreet_pairs.append((row.collected_at, row.file_date,
                                        arrival.collected_at, arrival.file_date, target))
        report["pairing"][f"roadside_{lead}"] = pairing_summary(roadside_pairs, config.split)
        report["pairing"][f"offstreet_{lead}"] = pairing_summary(offstreet_pairs, config.split)
    return report
