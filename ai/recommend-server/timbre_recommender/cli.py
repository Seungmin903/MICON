"""Command-line entry point for the local timbre recommender."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any, Sequence

import numpy as np

from .catalog import CatalogIndex, build_catalog_index
from .embedding import embed_vocal_audio
from .scoring import RankerConfig, recommend


def _write_or_print(payload: dict[str, Any], output: Path | None) -> None:
    rendered = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if output is None:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        print(rendered, end="")
        return
    destination = output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(rendered, encoding="utf-8")
    print(f"saved: {destination}")


def _build_index(args: argparse.Namespace) -> None:
    index_path = build_catalog_index(
        args.manifest,
        args.output_dir,
        max_segments=args.max_segments,
        force=args.force,
    )
    catalog = CatalogIndex.load(index_path.parent, verify_hashes=True)
    _write_or_print(
        {
            "index": str(index_path),
            "tracks": len(catalog.tracks),
            "max_segments_per_track": catalog.metadata["max_segments_per_track"],
            "models": catalog.metadata["models"],
        },
        None,
    )


def _recommend(args: argparse.Namespace) -> None:
    catalog = CatalogIndex.load(args.index_dir, verify_hashes=args.verify_hashes)
    query = {
        "ecapa": np.load(args.ecapa, allow_pickle=False),
        "singer_byol": np.load(args.singer_byol, allow_pickle=False),
    }
    config = RankerConfig(
        candidate_k_per_model=args.candidate_k,
        top_k=args.top_k,
        max_query_segments=args.max_query_segments,
    )
    result = recommend(
        catalog,
        query,
        config=config,
        excluded_track_ids=args.exclude_track_id,
    )
    _write_or_print(result, args.output)


def _embed(args: argparse.Namespace) -> None:
    result = embed_vocal_audio(
        args.audio,
        args.output_prefix,
        ecapa_checkpoint=args.ecapa_checkpoint,
        singer_repo=args.singer_repo,
        singer_checkpoint_root=args.singer_checkpoint_root,
        device=args.device,
        batch_size=args.batch_size,
        max_segments=args.max_segments,
        min_segments=args.min_segments,
        force=args.force,
    )
    _write_or_print(result, None)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m timbre_recommender",
        description="Local ECAPA + Singer-BYOL vocal timbre recommender",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    build = commands.add_parser(
        "build-index", help="build a versioned catalog from embedding .npy files"
    )
    build.add_argument("--manifest", type=Path, required=True)
    build.add_argument("--output-dir", type=Path, required=True)
    build.add_argument("--max-segments", type=int, default=9)
    build.add_argument("--force", action="store_true")
    build.set_defaults(handler=_build_index)

    rank = commands.add_parser("recommend", help="rank catalog tracks for one vocal query")
    rank.add_argument("--index-dir", type=Path, required=True)
    rank.add_argument("--ecapa", type=Path, required=True)
    rank.add_argument("--singer-byol", type=Path, required=True)
    rank.add_argument("--top-k", type=int, default=10)
    rank.add_argument("--candidate-k", type=int, default=50)
    rank.add_argument("--max-query-segments", type=int, default=12)
    rank.add_argument("--exclude-track-id", action="append", default=[])
    rank.add_argument("--verify-hashes", action="store_true")
    rank.add_argument("--output", type=Path)
    rank.set_defaults(handler=_recommend)

    embed = commands.add_parser(
        "embed-vocal", help="extract ECAPA and Singer-BYOL matrices from clean vocals"
    )
    embed.add_argument("--audio", type=Path, required=True)
    embed.add_argument("--output-prefix", type=Path, required=True)
    embed.add_argument("--ecapa-checkpoint", type=Path, required=True)
    embed.add_argument("--singer-repo", type=Path, required=True)
    embed.add_argument("--singer-checkpoint-root", type=Path, required=True)
    embed.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    embed.add_argument("--batch-size", type=int, default=8)
    embed.add_argument("--max-segments", type=int, default=12)
    embed.add_argument("--min-segments", type=int, default=5)
    embed.add_argument("--force", action="store_true")
    embed.set_defaults(handler=_embed)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    args.handler(args)
    return 0
