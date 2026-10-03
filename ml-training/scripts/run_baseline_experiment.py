from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.baseline_experiment import generate_baseline_experiment_report
from parking_ml.training_config import load_training_config


def main() -> int:
    parser = argparse.ArgumentParser(description="Run offline baseline and first model experiment report.")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    config = load_training_config(args.config)
    report = generate_baseline_experiment_report(args.input, config)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(f"Wrote baseline experiment report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
