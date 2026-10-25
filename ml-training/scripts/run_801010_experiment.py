from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.calibrated_experiment import run_calibrated_experiment
from parking_ml.three_way_experiment import run_three_way_experiment
from parking_ml.training_config import load_training_config


def main() -> int:
    parser = argparse.ArgumentParser(description="Train and evaluate an offline chronological 80/10/10 model experiment.")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--historical-report", type=Path, help="Optional path to the private 0993b17 report used as a reference.")
    parser.add_argument("--legacy-v0993b17", action="store_true", help="Reproduce the original 0993b17 three-method experiment.")
    args = parser.parse_args()

    config = load_training_config(args.config)
    if args.legacy_v0993b17:
        report = run_three_way_experiment(args.input, args.config, args.output, args.artifact_dir, config)
    else:
        report = run_calibrated_experiment(args.input, args.config, args.output, args.artifact_dir, config, args.historical_report)
    print(f"Wrote offline experiment report: {args.output}")
    print(f"Offline model artifacts: {args.artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
