# Model Training Workspace

This workspace is for offline model training and backtesting only. It must not
serve user queries, refresh official sources, or read the main system
`PUBLISHED` snapshot as live data.

## Boundaries

- Raw collector ZIP/CSV files stay outside Git, under ignored folders such as
  `data/private/collector-samples/` or `ml-training/data/`.
- Training code may read validated offline extracts, but it must preserve raw
  status values, label rules, schema versions, and rejection reasons.
- Online inference may only consume versioned model artifacts after backtest
  review. User queries must never train a model on demand.
- Baseline and ML scores are internal evidence until acceptance thresholds are
  defined and passed.

## Layout

```text
ml-training/
  README.md
  config/
    training-config.example.json
  scripts/
    plan_training_run.py
    generate_label_dry_run.py
    run_baseline_experiment.py
    run_801010_experiment.py
  src/
    parking_ml/
      dataset_split.py
      training_config.py
  tests/
    test_dataset_split.py
```

Ignored local working folders:

```text
ml-training/data/
ml-training/outputs/
ml-training/models/
ml-training/reports/private/
```

## Current Split

The first collector-quality inventory uses daily ZIP samples from 2026-09-24
through 2026-10-02. The default split is chronological:

- Train: 2026-09-24 through 2026-09-30
- Test: 2026-10-01 through 2026-10-02

This is about 75/25 with the current nine-day sample. It avoids row-random
leakage while staying close to a common train/test ratio. When more days are
available, move to a chronological 80/20 split or rolling-window evaluation.

## Frozen Experiment Audit (S3-021)

Run scripts/audit_frozen_experiment.py with --config, --input, and --output.
The output must be under data/private, ml-training/reports/private, or
ml-training/outputs. It reports aggregate state changes, circle overlap, split
boundary contamination, input hashes, and collection gaps; it fits no model.
Legacy baseline scores remain reproducible but are not admission evidence.
See docs/s3-021-model-experiment-handoff.md for results and follow-up conditions.

## 80/10/10 Offline Experiment

`training-config-80-10-10.example.json` defines a chronological date split for
the 2026-09-24 through 2026-10-24 local sample: 25 train days, 3 validation
days, and 3 test days. This is 80.6/9.7/9.7 by calendar days; accepted label
counts can differ because unknown observations and split-crossing arrivals are
excluded. The runner checks that each feature and arrival label remain in one
split, builds roadside circles only from the training period, and uses Taipei
time for calendar features.

The calibrated runner uses the versioned collected_at_taipei_v1 policy. It
splits by the date of collected_at_utc after conversion to Asia/Taipei, and
requires an explicit timezone and matching collected_date_taipei on both
as-of and arrival rows. An arrival in another split is rejected. Missing or
invalid required fields are rejected or counted as parser skips; reports expose
date mismatch rejection counts. Features continue to use as-of observations
only. The original --legacy-v0993b17 runner keeps file_date_v0993b17 so its
historical results remain reproducible.

compareLegacyRoadsidePolicy controls whether the calibrated report includes
the legacy partial-observation roadside cohort. When false, the runner omits
that cohort and its coverage audit.

Run the experiment and keep its report and model candidates in ignored local
folders:

```powershell
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config-80-10-10.example.json
python ml-training/scripts/run_801010_experiment.py --config ml-training/config/training-config-80-10-10.example.json --input data/private/collector-samples --output data/private/collector-samples/experiment-80-10-10-2026-10-25.md --artifact-dir ml-training/models/experiment-80-10-10-2026-10-25
```

The runner compares `train_positive_rate`, fixed `current_state_persistence`,
train-only Laplace-smoothed `calibrated_state_persistence_v1`, and
`logistic_regression_v1` on the same cohort rows. Validation Brier selects a
candidate; test evaluation starts only after that selection and requires the
configured sample count and both classes. Defaults are 500 train labels and 100
validation/test labels, plus both labels in each split. These are conservative
minimum-data gates, not statistical guarantees or production acceptance.

For the new roadside cohort, `roadside_complete_circle_v1` freezes candidate
circles using train-only rows and rejects pairs unless as-of, arrival, and
common spot coverage are complete. The report also retains
`roadside_partial_observation_v0993b17` as a separately labeled compatibility
cohort; their scores must not be compared as if the labels matched. Calibrated
rates use alpha 1.0 and require 20 train observations per availability state;
smaller groups fall back to the smoothed train-wide positive rate.

The report includes admission reasons, coverage ratios, false-positive and
near-full metrics, state-transition and per-Taipei-day summaries, and calibration
bins (small buckets are marked `LOW_SAMPLE`). JSON artifacts use schema v2 and
`as_of_hour_*` feature names. All results and artifacts remain aggregate,
ignored local evidence marked `EXPERIMENT_ONLY`; they are not production model
approval or ETA-aware product predictions.

To reproduce the exact original three-method runner on the previously analyzed
dates, pass `--legacy-v0993b17` and use a new ignored output path. To include the
old report hash beside the new experiment, pass `--historical-report`:

```powershell
python ml-training/scripts/run_801010_experiment.py --config ml-training/config/training-config-80-10-10.example.json --input data/private/collector-samples --output data/private/collector-samples/calibrated-persistence-2026-10-25.md --artifact-dir ml-training/models/calibrated-persistence-2026-10-25 --historical-report data/private/collector-samples/replication-0993b17-2026-10-25.md
```

## Commands

Preview the current training plan:

```powershell
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config.example.json
```

Generate a private label dry-run report:

```powershell
python ml-training/scripts/generate_label_dry_run.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/label-dry-run-2026-09-24_2026-10-02.md
```

Generate a private baseline experiment report:

```powershell
python ml-training/scripts/run_baseline_experiment.py --config ml-training/config/training-config.example.json --input data/private/collector-samples --output data/private/collector-samples/baseline-experiment-2026-09-24_2026-10-02.md
```

Run lightweight tests:

```powershell
python -m unittest discover -s ml-training/tests
```

These commands do not publish a model. They only validate the offline training
workspace contract, label-pairing dry run, and private aggregate baseline report.
