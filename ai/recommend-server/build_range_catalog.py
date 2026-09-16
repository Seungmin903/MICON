"""Build bundled VocalScope note-distribution profiles for a song catalog."""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
from typing import Any, Mapping

from pitch_engine import extract_vocal_melody
from pitch_profile import PITCH_EXTRACTION_VERSION, summarize_pitch_profile
from timbre_recommender.pitch_range import (
    KEY_SHIFT_BOUNDS,
    RANGE_SCORING_VERSION,
    canonical_json_sha256,
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _runtime_versions() -> dict[str, str]:
    import torch

    return {
        "torch": str(torch.__version__),
        "torchcrepe": version("torchcrepe"),
        "librosa": version("librosa"),
        "numpy": version("numpy"),
    }


def _analyze_one(arguments: tuple[str, str, str]) -> tuple[str, dict[str, Any]]:
    track_id, path_string, device = arguments
    path = Path(path_string)
    melody = extract_vocal_melody(path, None, device, return_frames=False)
    profile = summarize_pitch_profile(
        melody["notes"],
        profile_kind="catalog_song",
        f0_agreement_ratio=melody.get("f0AgreementRatio"),
        octave_mismatch_ratio=melody.get("octaveMismatchRatio"),
        compared_frames=melody.get("comparedFrames"),
    )
    profile["sourceSha256"] = _sha256(path)
    profile["pitchMethod"] = melody.get("pitchMethod")
    return track_id, profile


def write_range_catalog(
    *,
    index_path: Path,
    output_path: Path,
    profiles: Mapping[str, Mapping[str, Any]],
    source_hint: str,
) -> dict[str, Any]:
    """Write already-computed profiles beside an index with strict provenance."""

    index_path = index_path.resolve()
    output_path = output_path.resolve()
    if output_path.parent != index_path.parent:
        raise ValueError("range profile output must be written beside index.json")
    index_sha256 = canonical_json_sha256(index_path)
    index = json.loads(index_path.read_text(encoding="utf-8"))
    expected = {str(item["track_id"]) for item in index["tracks"]}
    actual = {str(track_id) for track_id in profiles}
    if expected != actual:
        raise ValueError(
            "range/index track mismatch: "
            f"missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"
        )
    payload = {
        "schemaVersion": 1,
        "scoringVersion": RANGE_SCORING_VERSION,
        "pitchExtractionVersion": PITCH_EXTRACTION_VERSION,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "profileMethod": "VocalScope CREPE full + Viterbi + pYIN; duration-weighted note histogram",
        "keyShiftRange": list(KEY_SHIFT_BOUNDS),
        "generationRuntime": _runtime_versions(),
        "source": {
            "indexFile": index_path.name,
            "indexCanonicalSha256": index_sha256,
            "stemsRootHint": source_hint,
        },
        "profiles": {
            key: dict(profiles[key]) for key in sorted(profiles)
        },
    }
    if canonical_json_sha256(index_path) != index_sha256:
        raise RuntimeError("index.json changed while range profiles were being written")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_name(f".{output_path.name}.tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, output_path)
    return payload


def build(
    *, index_path: Path, stems_root: Path, output_path: Path, device: str, workers: int
) -> dict[str, Any]:
    if device == "cuda" and workers != 1:
        raise ValueError("CUDA range extraction supports exactly one worker to avoid GPU OOM")
    initial_index_sha256 = canonical_json_sha256(index_path)
    index = json.loads(index_path.read_text(encoding="utf-8"))
    tasks: list[tuple[str, str, str]] = []
    for item in index["tracks"]:
        track_id = str(item["track_id"])
        stem = stems_root / track_id / "vocals.wav"
        if not stem.is_file():
            raise FileNotFoundError(f"missing vocal stem for {track_id}: {stem}")
        tasks.append((track_id, str(stem.resolve()), device))

    profiles: dict[str, dict[str, Any]] = {}
    if workers <= 1:
        iterator = map(_analyze_one, tasks)
        for track_id, profile in iterator:
            profiles[track_id] = profile
            print(f"[{len(profiles):02d}/{len(tasks):02d}] {track_id}", flush=True)
    else:
        with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as executor:
            futures = {executor.submit(_analyze_one, task): task[0] for task in tasks}
            for future in concurrent.futures.as_completed(futures):
                track_id, profile = future.result()
                profiles[track_id] = profile
                print(f"[{len(profiles):02d}/{len(tasks):02d}] {track_id}", flush=True)

    if canonical_json_sha256(index_path) != initial_index_sha256:
        raise RuntimeError("index.json changed while range profiles were being generated")
    return write_range_catalog(
        index_path=index_path,
        output_path=output_path,
        profiles=profiles,
        source_hint="music0828/separation/stems/<track_id>/vocals.wav",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--stems-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.output.exists() and not args.force:
        raise FileExistsError(f"output exists: {args.output}; pass --force to replace it")
    payload = build(
        index_path=args.index.resolve(),
        stems_root=args.stems_root.resolve(),
        output_path=args.output.resolve(),
        device=args.device,
        workers=max(1, args.workers),
    )
    print(json.dumps({"tracks": len(payload["profiles"]), "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
