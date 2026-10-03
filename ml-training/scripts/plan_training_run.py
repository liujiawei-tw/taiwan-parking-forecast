#!/usr/bin/env python3
"""Print the configured offline training plan without training a model."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

from parking_ml.training_config import load_training_config  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path, help="Path to training config JSON.")
    args = parser.parse_args()

    config = load_training_config(args.config)
    print(config.render_plan())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
