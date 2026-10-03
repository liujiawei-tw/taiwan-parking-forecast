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
