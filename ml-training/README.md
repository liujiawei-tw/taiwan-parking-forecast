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

## Off-street Shadow Test MVP

run_offstreet_shadow_test.py provides two local-only phases for one off-street
car park. It reads ignored collector ZIP files as an offline proxy; the main
system currently has no public read-only historical-observation export for this
runner. It does not call an official API, Google Routes, or the online
predictions path.

Phase one writes private JSONL prediction rows using only each as-of row and
train-period labels. It supports the already evaluated 30- and 60-minute leads,
caps the first pass at 50 predictions per lead, and records the current-state
and train-only calibrated persistence probabilities. Logistic regression is
included only when a v2 experiment artifact matches the input ZIP hash, exact
training-config hash, split dates, feature schema, and train sample count;
otherwise its probability is null and the report records why.

Phase two runs after the target arrival time. It requires a qualified
observation within plus or minus 15 minutes by default and a source response
timestamp within plus or minus 10 minutes of the collector timestamp. Both
tolerances are printed in the aggregate report. Unknown, negative, stale,
timezone-invalid, Taipei-date-mismatched, and missing arrival observations
remain unverifiable. The private result JSONL keeps per-row matches and
reasons; Markdown and JSON reports contain aggregate counts and metrics only.
Outputs are EXPERIMENT_ONLY and never update production predictions.

### 時間語意（PR #97 review 修正）

產物會分開記錄 `collectedAtUtc`、`sourceResponseAtUtc`、`predictionAtUtc`
與 `targetArrivalAtUtc`。as-of 特徵必須在預測時間時已收集且已收到來源回應；
來源回應晚於預測時間、缺少／無效時間或相對預測時間超過新鮮度門檻時，會拒收。
新鮮度門檻是 Shadow MVP 的明確實驗參數，預設 15 分鐘並寫進報告；它不是主系統
產品 TTL 或上線准入門檻。

- `--mode REPLAY` 以每筆資料的 `max(collectedAt, sourceResponseAt)` 作歷史模擬預測時間，
  lead 從該時間起算。報告標示 `RETROSPECTIVE_REPLAY`、區分實際執行時間，並明確說明
  這不是前瞻測試或新 holdout。
- `--mode PROSPECTIVE` 以 runner 執行時間預測，只採既有 test 日期範圍之後、當下已收到且
  新鮮的每場最新觀測；目標時間到達前保持 `PENDING`。若抵達列已收集但來源回應晚於核對
  時間，也保持 `PENDING`；缺失或不合格觀測為 `UNVERIFIABLE`。只有舊 ZIP 時會報告
  `NO_QUALIFYING_PROSPECTIVE_DATA`，不會將回放結果冒充前瞻結果。

本機沒有已確認的產品 freshness 契約，所以執行時明確指定 15 分鐘：

Use a new ignored output directory for each run. Keep the prediction and result
JSONL files under data/private/, ml-training/data/, or
ml-training/reports/private/:

    python ml-training/scripts/run_offstreet_shadow_test.py predict --mode REPLAY --max-as-of-age-minutes 15 --input data/private/collector-samples --training-config ml-training/config/training-config-80-10-10.example.json --output data/private/collector-samples/shadow-mvp-run-2026-10-25/predictions.jsonl --logistic-artifact-dir ml-training/models/calibrated-persistence-2026-10-25
    python ml-training/scripts/run_offstreet_shadow_test.py verify --input data/private/collector-samples --predictions data/private/collector-samples/shadow-mvp-run-2026-10-25/predictions.jsonl --results data/private/collector-samples/shadow-mvp-run-2026-10-25/results.jsonl --report-json data/private/collector-samples/shadow-mvp-run-2026-10-25/report.json --report-markdown data/private/collector-samples/shadow-mvp-run-2026-10-25/report.md

This first sample target is 50–100 verifiable cases, not a statistical
admission gate. The report remains EXPLORATORY_ONLY when samples are below 50,
both labels are not present, or fewer than five distinct arrival dates are
covered. The fixed leads do not represent arbitrary Google ETA predictions.

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
