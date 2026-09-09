"""Plan, build, finalize, and publish a large local recommendation catalog."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from timbre_recommender.bulk_catalog import (
    build_tracks,
    finalize_catalog,
    publish_catalog,
)
from timbre_recommender.catalog_planner import build_catalog_plan, write_catalog_plan


ROOT = Path(__file__).resolve().parent
DEFAULT_WORKSPACE = ROOT / "workspace" / "catalog-build"
DEFAULT_CONFIG = ROOT / "workspace" / "recommender" / "config.json"
DEFAULT_RELEASE = DEFAULT_WORKSPACE / "release" / "catalog-index"
DEFAULT_PUBLISHED = ROOT / "recommender-assets" / "catalog-index"


def _config(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise FileNotFoundError(f"recommender config not found: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("recommender config must be a JSON object")
    return value


def _json_object(path: Path, *, label: str) -> dict[str, object]:
    if not path.is_file():
        raise FileNotFoundError(f"{label} not found: {path}")
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    plan = commands.add_parser("plan", help="match, deduplicate, and filter local audio")
    plan.add_argument("--audio-root", type=Path, required=True)
    plan.add_argument("--metadata", type=Path, required=True)
    plan.add_argument("--artist-policy", type=Path, required=True)
    plan.add_argument("--workspace", type=Path, default=DEFAULT_WORKSPACE)
    plan.add_argument("--hash-cache", type=Path)

    build = commands.add_parser("build", help="process selected audio with resumable checkpoints")
    build.add_argument("--selection", type=Path, required=True)
    build.add_argument("--workspace", type=Path, default=DEFAULT_WORKSPACE)
    build.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    build.add_argument("--separator", choices=("htdemucs", "melband"), default="htdemucs")
    build.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    build.add_argument("--limit", type=int)
    build.add_argument("--continue-on-error", action="store_true")

    finalize = commands.add_parser("finalize", help="assemble completed checkpoints")
    finalize.add_argument("--selection", type=Path, required=True)
    finalize.add_argument("--workspace", type=Path, default=DEFAULT_WORKSPACE)
    finalize.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    finalize.add_argument("--output", type=Path, default=DEFAULT_RELEASE)
    finalize.add_argument("--separator", choices=("htdemucs", "melband"), default="htdemucs")
    finalize.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    finalize.add_argument("--allow-incomplete", action="store_true")

    publish = commands.add_parser(
        "publish", help="swap a verified release into Git assets with rollback"
    )
    publish.add_argument("--release", type=Path, default=DEFAULT_RELEASE)
    publish.add_argument("--output", type=Path, default=DEFAULT_PUBLISHED)

    args = parser.parse_args()
    if args.command == "plan":
        workspace = args.workspace.resolve()
        catalog_plan = build_catalog_plan(
            args.audio_root.resolve(),
            args.metadata.resolve(),
            _json_object(args.artist_policy.resolve(), label="artist policy"),
            hash_cache_path=(
                args.hash_cache.resolve()
                if args.hash_cache is not None
                else workspace / "audio-hashes.json"
            ),
        )
        paths = write_catalog_plan(catalog_plan, workspace)
        result = {
            "summary": catalog_plan.summary,
            "outputs": {key: str(path.resolve()) for key, path in paths.items()},
        }
    elif args.command == "build":
        result = build_tracks(
            selection_path=args.selection.resolve(),
            workspace=args.workspace.resolve(),
            config=_config(args.config.resolve()),
            model_server_root=ROOT,
            separator=args.separator,
            device=args.device,
            limit=args.limit,
            continue_on_error=args.continue_on_error,
        )
    elif args.command == "finalize":
        result = finalize_catalog(
            selection_path=args.selection.resolve(),
            workspace=args.workspace.resolve(),
            config=_config(args.config.resolve()),
            model_server_root=ROOT,
            output_dir=args.output.resolve(),
            separator=args.separator,
            device=args.device,
            require_all=not args.allow_incomplete,
        )
    else:
        result = publish_catalog(
            release_dir=args.release.resolve(), destination_dir=args.output.resolve()
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
