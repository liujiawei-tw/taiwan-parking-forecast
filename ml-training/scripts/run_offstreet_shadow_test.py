"""Run the two private, offline phases of the off-street shadow test."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ml-training" / "src"))

from parking_ml.offstreet_shadow import (  # noqa: E402
    DEFAULT_ARRIVAL_TOLERANCE_MINUTES,
    DEFAULT_MAX_PREDICTIONS_PER_LEAD,
    DEFAULT_SOURCE_TIME_TOLERANCE_MINUTES,
    generate_predictions,
    verify_predictions,
)

DEFAULT_TRAINING_CONFIG = ROOT / "ml-training" / "config" / "training-config-80-10-10.example.json"


def parse_timestamp(value: str | None) -> datetime | None:
    if value is None:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise argparse.ArgumentTypeError("時間必須帶時區，例如 2026-10-25T12:00:00+08:00")
    return parsed.astimezone(timezone.utc)


def require_private_path(path: Path, label: str) -> None:
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(ROOT.resolve())
    except ValueError:
        return
    parts = [part.lower() for part in relative.parts]
    private = (
        parts[:2] == ["data", "private"]
        or parts[:2] == ["ml-training", "data"]
        or parts[:3] == ["ml-training", "reports", "private"]
    )
    if not private:
        raise ValueError(f"{label} 必須寫入 Git 忽略的 private 目錄：{path}")


def require_new_paths(paths: list[Path]) -> None:
    existing = [str(path) for path in paths if path.exists()]
    if existing:
        raise FileExistsError("拒絕覆寫既有輸出：" + ", ".join(existing))


def main() -> int:
    parser = argparse.ArgumentParser(description="離線路外停車 Shadow Test；不呼叫線上 API，也不寫正式 predictions。")
    commands = parser.add_subparsers(dest="command", required=True)

    predict = commands.add_parser("predict", help="產生不含抵達標籤的私有預測 JSONL")
    predict.add_argument("--input", type=Path, required=True, help="本機 collector ZIP 檔或其資料夾")
    predict.add_argument("--training-config", type=Path, default=DEFAULT_TRAINING_CONFIG)
    predict.add_argument("--output", type=Path, required=True, help="預測列 JSONL，必須位於 ignored private 目錄")
    predict.add_argument("--logistic-artifact-dir", type=Path, help="合格 v2 Logistic artifact 資料夾；缺省時不輸出 Logistic 機率")
    predict.add_argument("--generated-at", type=parse_timestamp, help="預測產生時間，必須帶時區；預設目前時間")
    predict.add_argument("--arrival-tolerance-minutes", type=int, default=DEFAULT_ARRIVAL_TOLERANCE_MINUTES)
    predict.add_argument("--source-time-tolerance-minutes", type=int, default=DEFAULT_SOURCE_TIME_TOLERANCE_MINUTES)
    predict.add_argument("--max-predictions-per-lead", type=int, default=DEFAULT_MAX_PREDICTIONS_PER_LEAD)

    verify = commands.add_parser("verify", help="等目標時間經過後配對實際觀測，僅輸出 aggregate Markdown／JSON")
    verify.add_argument("--input", type=Path, required=True, help="本機 collector ZIP 檔或其資料夾")
    verify.add_argument("--predictions", type=Path, required=True, help="predict 階段的私有 JSONL")
    verify.add_argument("--results", type=Path, required=True, help="逐筆核對結果 JSONL，必須位於 ignored private 目錄")
    verify.add_argument("--report-json", type=Path, required=True, help="aggregate JSON 報告")
    verify.add_argument("--report-markdown", type=Path, required=True, help="aggregate Markdown 報告")
    verify.add_argument("--verified-at", type=parse_timestamp, help="核對時間，必須帶時區；預設目前時間")

    args = parser.parse_args()
    try:
        if args.command == "predict":
            require_private_path(args.output, "預測列")
            require_new_paths([args.output])
            metadata = generate_predictions(
                args.input,
                args.training_config,
                args.output,
                generated_at=args.generated_at,
                logistic_artifact_dir=args.logistic_artifact_dir,
                arrival_tolerance_minutes=args.arrival_tolerance_minutes,
                source_time_tolerance_minutes=args.source_time_tolerance_minutes,
                max_predictions_per_lead=args.max_predictions_per_lead,
            )
            print(json.dumps({
                "status": metadata["status"],
                "publishToOnlineSystem": metadata["publishToOnlineSystem"],
                "sourceType": metadata["sourceType"],
                "sourceZipCount": metadata["sourceZipCount"],
                "sourceRows": metadata["sourceRows"],
                "candidateAuditByLead": metadata["candidateAuditByLead"],
                "trainingSamplesByLead": metadata["trainingSamplesByLead"],
                "modelsByLead": metadata["modelsByLead"],
            }, ensure_ascii=False, indent=2))
            return 0

        require_private_path(args.predictions, "預測列")
        require_private_path(args.results, "逐筆結果")
        require_new_paths([args.results, args.report_json, args.report_markdown])
        report = verify_predictions(
            args.input,
            args.predictions,
            results_path=args.results,
            report_json_path=args.report_json,
            report_markdown_path=args.report_markdown,
            verified_at=args.verified_at,
        )
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (FileExistsError, ValueError, OSError) as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
