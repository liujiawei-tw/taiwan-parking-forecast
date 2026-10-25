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

Run the experiment and keep its report and model candidates in ignored local
folders:

```powershell
python ml-training/scripts/plan_training_run.py --config ml-training/config/training-config-80-10-10.example.json
python ml-training/scripts/run_801010_experiment.py --config ml-training/config/training-config-80-10-10.example.json --input data/private/collector-samples --output data/private/collector-samples/experiment-80-10-10-2026-10-25.md --artifact-dir ml-training/models/experiment-80-10-10-2026-10-25
```

Validation selects the comparison method by Brier score; the test dates remain
held out. Outputs are aggregate evidence and offline candidate weights only.
They are not production model approval or an ETA-aware product prediction.

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
