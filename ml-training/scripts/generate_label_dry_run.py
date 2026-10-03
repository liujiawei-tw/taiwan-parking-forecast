#!/usr/bin/env python3
"""Generate an offline label dry-run report without writing row-level labels."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

from parking_ml.label_dry_run import generate_label_dry_run_report  # noqa: E402
from parking_ml.training_config import load_training_config  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path, help="Path to training config JSON.")
    parser.add_argument("--input", type=Path, help="Collector ZIP directory. Defaults to config source path.")
    parser.add_argument("--output", type=Path, help="Optional Markdown report path.")
    args = parser.parse_args()

    config = load_training_config(args.config)
    input_path = args.input or Path(config.local_sample_directory)
    report = generate_label_dry_run_report(input_path=input_path, config=config)

    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
