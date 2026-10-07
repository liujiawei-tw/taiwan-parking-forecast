from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from parking_ml.collector_reader import iter_zip_paths
from parking_ml.experiment_audit import audit_observations
from parking_ml.label_dry_run import read_observations
from parking_ml.training_config import load_training_config


def main():
    parser = argparse.ArgumentParser(description="Audit existing offline samples without model fitting.")
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    workspace = ROOT.parent.resolve()
    output = args.output.resolve()
    private_roots = [workspace / "data/private", ROOT / "reports/private", ROOT / "outputs"]
    if output.suffix != ".json" or not any(output.is_relative_to(path) for path in private_roots):
        parser.error("Output must be a JSON file inside a private workspace directory")
    config = load_training_config(args.config)
    files = iter_zip_paths(args.input)
    inputs = []
    for path in files:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        inputs.append({"file": path.name, "sha256": digest})
    observations = read_observations(args.input)
    report = audit_observations(observations, config)
    report["config_sha256"] = hashlib.sha256(args.config.read_bytes()).hexdigest()
    report["inputs"] = inputs
    report["note"] = "Frozen legacy behavior; diagnostics only, not an acceptance gate or new model."
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote aggregate audit: {output.name}")


if __name__ == "__main__":
    main()
