from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import DATE_POLICY_TIMESTAMP_TAIPEI_V1, build_offstreet_samples
from parking_ml.calibrated_experiment import CalibratedPersistenceModel, calibrated_persistence_artifact, hash_files
from parking_ml.collector_reader import iter_zip_paths
from parking_ml.label_dry_run import LabelStats, read_observations, settings_from_config
from parking_ml.training_config import load_training_config


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export an experimental off-street calibrated-persistence artifact from train-only labels."
    )
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    config = load_training_config(args.config)
    settings = settings_from_config(config)
    observations = read_observations(args.input, datasets={"offstreet"})
    admission: dict[tuple[str, int], LabelStats] = {}
    samples = build_offstreet_samples(
        observations.offstreet_by_lot,
        config,
        settings,
        admission_audit=admission,
        date_policy_version=DATE_POLICY_TIMESTAMP_TAIPEI_V1,
    )
    zip_paths = iter_zip_paths(args.input)
    source_hash = hash_files(zip_paths)
    config_hash = hashlib.sha256(args.config.read_bytes()).hexdigest()
    min_train_samples = int(config.baseline_experiment.get("minTrainSamples", 500))
    alpha = float(config.baseline_experiment.get("calibratedPersistenceAlpha", 1.0))
    min_state_samples = int(config.baseline_experiment.get("calibratedPersistenceMinStateSamples", 20))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    exported = []
    for lead in settings.lead_minutes:
        train = [sample for sample in samples if sample.lead_minutes == lead and sample.split == "train"]
        if len(train) < min_train_samples or len({sample.label for sample in train}) < 2:
            raise ValueError(f"offstreet_train_gate_failed_for_{lead}m")
        model = CalibratedPersistenceModel.fit(train, alpha=alpha, minimum_state_samples=min_state_samples)
        artifact = calibrated_persistence_artifact(
            lead=lead,
            model=model,
            train=train,
            config=config,
            source_hash=source_hash,
            config_hash=config_hash,
        )
        path = args.output_dir / f"offstreet-lead-{lead}m-calibrated-persistence-experiment-v1.json"
        path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        exported.append({
            "path": path.as_posix(),
            "modelId": artifact["modelId"],
            "trainingSamples": artifact["trainingSamples"],
            "trainingPositiveRate": artifact["trainingPositiveRate"],
            "calibrationParameters": artifact["calibrationParameters"],
        })

    print(json.dumps({
        "status": "EXPERIMENT_ONLY",
        "publishToOnlineSystem": False,
        "dataset": "offstreet",
        "coveragePolicyVersion": "OFFSTREET",
        "sourceRows": observations.source_rows.get("offstreet", 0),
        "skippedRows": observations.skipped_rows.get("offstreet_parse_or_missing_key", 0),
        "sourceZipSha256": source_hash,
        "trainingConfigSha256": config_hash,
        "artifacts": exported,
        "notices": [
            "Artifacts use train split labels only.",
            "Validation and test dates remain exploratory and do not establish production admission.",
            "No row-level samples are written to the artifact.",
        ],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
