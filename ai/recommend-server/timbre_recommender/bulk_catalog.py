"""Resumable, auditable catalog generation for local song collections."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from importlib.metadata import PackageNotFoundError, version
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time
from typing import Any, Iterable, Mapping

import imageio_ffmpeg
import numpy as np

from build_range_catalog import write_range_catalog
from pitch_engine import extract_vocal_melody
from pitch_profile import PITCH_EXTRACTION_VERSION, summarize_pitch_profile
from separation import BACKENDS, separate
from .catalog import CatalogIndex, build_catalog_index, sha256_file
from .contracts import MODEL_CONTRACTS
from .embedding import (
    ECAPA_CHECKPOINT_SHA256,
    OFFICIAL_SINGER_BYOL_REVISION,
    SINGER_BYOL_CHECKPOINT_SHA256,
    SINGER_BYOL_HPARAMS_SHA256,
    VocalEmbeddingRuntime,
    embed_vocal_audio,
)
from .pitch_range import RANGE_CATALOG_FILENAME, RangeCatalog


BUILD_SCHEMA_VERSION = 1
SELECTION_SCHEMA_VERSION = 1
BUILD_PIPELINE_VERSION = "catalog-build-v1"
MIX_CHANNELS = 2
MIX_SAMPLE_RATE = 44_100
EMBED_BATCH_SIZE = 8
EMBED_MAX_SEGMENTS = 12
EMBED_MIN_SEGMENTS = 5
INDEX_MAX_SEGMENTS = 9
HTDEMUCS_MODEL_FILENAME = "955717e8-8726e21a.th"
RUNTIME_DISTRIBUTIONS = (
    "demucs",
    "imageio-ffmpeg",
    "librosa",
    "nnAudio",
    "numpy",
    "scipy",
    "soundfile",
    "speechbrain",
    "torch",
    "torchaudio",
    "torchcrepe",
)
MANAGED_RELEASE_FILES = ("ecapa.npy", "singer_byol.npy", RANGE_CATALOG_FILENAME, "index.json")
SAFE_METADATA_KEYS = (
    "genres",
    "album_name",
    "release_date",
    "display_image_url",
    "image_type",
    "languages",
    "tj_id",
    "tj_aliases",
)
TRACK_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")


@dataclass(frozen=True)
class SelectionTrack:
    track_id: str
    audio_path: Path
    audio_sha256: str
    title: str
    artist: str
    metadata: dict[str, Any]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _atomic_write_json(path: Path, payload: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for attempt in range(8):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 7:
                raise
            time.sleep(0.025 * (attempt + 1))


def _canonical_hash(payload: Mapping[str, Any]) -> str:
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _distribution_versions() -> dict[str, str]:
    result: dict[str, str] = {}
    for distribution in RUNTIME_DISTRIBUTIONS:
        try:
            result[distribution] = version(distribution)
        except PackageNotFoundError:
            result[distribution] = "missing"
    return result


def _separator_provenance(separator: str) -> dict[str, Any]:
    if separator == "htdemucs":
        import torch

        legacy_checkpoint = (
            Path(torch.hub.get_dir()).resolve()
            / "checkpoints"
            / HTDEMUCS_MODEL_FILENAME
        )
        if not legacy_checkpoint.is_file():
            # Demucs 4.0 used a single legacy Torch Hub checkpoint. Demucs 4.1
            # resolves the same named model from Hugging Face as a YAML manifest
            # plus one or more safetensors files. Trigger either download before
            # fingerprinting so every resumed song shares the exact weight hash.
            from demucs.pretrained import get_model

            get_model("htdemucs")
        if legacy_checkpoint.is_file():
            return {
                "backend": separator,
                "model": "htdemucs",
                "source": "torch_hub_legacy",
                "checkpoint_filename": legacy_checkpoint.name,
                "checkpoint_sha256": sha256_file(legacy_checkpoint),
            }

        try:
            import yaml
            from demucs.hf import DEFAULT_NAMESPACE, hf_repo_name
            from huggingface_hub import hf_hub_download

            repo_id = f"{DEFAULT_NAMESPACE}/{hf_repo_name('htdemucs')}"
            manifest = Path(
                hf_hub_download(repo_id, "htdemucs.yaml")
            ).resolve()
            bag = yaml.safe_load(manifest.read_text(encoding="utf-8"))
            signatures = bag.get("models") if isinstance(bag, dict) else None
            if not isinstance(signatures, list) or not signatures:
                raise ValueError("HTDemucs manifest has no model signatures")
            checkpoints = [
                Path(hf_hub_download(repo_id, f"{signature}.safetensors")).resolve()
                for signature in signatures
            ]
            revisions = {manifest.parent.name, *(path.parent.name for path in checkpoints)}
            if len(revisions) != 1:
                raise RuntimeError("HTDemucs assets resolved from different revisions")
            return {
                "backend": separator,
                "model": "htdemucs",
                "source": "huggingface",
                "repository": repo_id,
                "revision": revisions.pop(),
                "manifest_filename": manifest.name,
                "manifest_sha256": sha256_file(manifest),
                "checkpoints": [
                    {"filename": path.name, "sha256": sha256_file(path)}
                    for path in checkpoints
                ],
            }
        except Exception as error:
            raise FileNotFoundError(
                "HTDemucs checkpoint was not found in either the legacy Torch Hub "
                "cache or the Demucs 4.1 Hugging Face cache"
            ) from error

    if separator == "melband":
        import contextlib
        import io
        from mel_band_roformer import ensure_model_assets
        from separation import MELBAND_MODEL_KEY

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(
            io.StringIO()
        ):
            model_path, config_path = ensure_model_assets(MELBAND_MODEL_KEY)
        model = Path(model_path).resolve()
        config = Path(config_path).resolve()
        return {
            "backend": separator,
            "model": MELBAND_MODEL_KEY,
            "checkpoint_sha256": sha256_file(model),
            "config_sha256": sha256_file(config),
        }
    raise ValueError(f"unsupported separator: {separator}")


def _runtime_provenance(separator: str, compute_device: str) -> dict[str, Any]:
    import torchcrepe

    ffmpeg = Path(imageio_ffmpeg.get_ffmpeg_exe()).resolve()
    crepe_full = Path(torchcrepe.__file__).resolve().parent / "assets" / "full.pth"
    if not crepe_full.is_file():
        raise FileNotFoundError(f"TorchCREPE full checkpoint not found: {crepe_full}")
    return {
        "python": platform.python_version(),
        "compute_device": compute_device,
        "distributions": _distribution_versions(),
        "ffmpeg": {
            "version": imageio_ffmpeg.get_ffmpeg_version(),
            "binary_sha256": sha256_file(ffmpeg),
        },
        "pitch_model": {
            "model": "torchcrepe-full",
            "checkpoint_sha256": sha256_file(crepe_full),
        },
        "separator": _separator_provenance(separator),
    }


def _safe_metadata(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {key: value[key] for key in SAFE_METADATA_KEYS if key in value}


def load_selection(path: Path | str, *, include_only: bool = True) -> list[SelectionTrack]:
    source = Path(path).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"selection file not found: {source}")
    tracks: list[SelectionTrack] = []
    seen_ids: set[str] = set()
    seen_hashes: set[str] = set()
    for line_number, line in enumerate(source.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid selection JSON at line {line_number}: {error}") from error
        include = bool(row.get("include", row.get("decision") == "include"))
        if include_only and not include:
            continue
        track_id = str(row.get("track_id", "")).strip()
        if not TRACK_ID_PATTERN.fullmatch(track_id):
            raise ValueError(f"invalid track_id at line {line_number}: {track_id!r}")
        audio_hash = str(row.get("audio_sha256", "")).strip().lower()
        if not re.fullmatch(r"[0-9a-f]{64}", audio_hash):
            raise ValueError(f"invalid audio_sha256 for {track_id}")
        if track_id in seen_ids:
            raise ValueError(f"duplicate selected track_id: {track_id}")
        if audio_hash in seen_hashes:
            raise ValueError(
                f"duplicate selected audio content for {track_id}; keep one canonical track and aliases"
            )
        audio_path = Path(str(row.get("audio_path", ""))).resolve()
        title = str(row.get("title", "")).strip()
        artist = str(row.get("artist", "")).strip()
        if not title or not artist:
            raise ValueError(f"title and artist are required for {track_id}")
        seen_ids.add(track_id)
        seen_hashes.add(audio_hash)
        tracks.append(
            SelectionTrack(
                track_id=track_id,
                audio_path=audio_path,
                audio_sha256=audio_hash,
                title=title,
                artist=artist,
                metadata=_safe_metadata(row.get("metadata")),
            )
        )
    if not tracks:
        raise ValueError("selection contains no included tracks")
    return sorted(tracks, key=lambda item: item.track_id)


def _require_model_files(config: Mapping[str, Any]) -> dict[str, Path]:
    paths = {
        "ecapa": Path(str(config.get("ecapa_checkpoint", ""))).resolve(),
        "singer_repo": Path(str(config.get("singer_repo", ""))).resolve(),
        "singer_model": (
            Path(str(config.get("singer_checkpoint_root", ""))) / "byol" / "model.pt"
        ).resolve(),
        "singer_hparams": (
            Path(str(config.get("singer_checkpoint_root", ""))) / "byol" / "hyperparams.yaml"
        ).resolve(),
    }
    required = {
        "ecapa": paths["ecapa"],
        "singer_model": paths["singer_model"],
        "singer_hparams": paths["singer_hparams"],
        "singer_code": paths["singer_repo"] / "singer_identity" / "model.py",
    }
    missing = [f"{key}: {path}" for key, path in required.items() if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"catalog model files are missing: {missing}")
    expected_hashes = {
        "ecapa": ECAPA_CHECKPOINT_SHA256,
        "singer_model": SINGER_BYOL_CHECKPOINT_SHA256,
        "singer_hparams": SINGER_BYOL_HPARAMS_SHA256,
    }
    for key, expected in expected_hashes.items():
        actual = sha256_file(paths[key])
        if actual != expected:
            raise RuntimeError(f"{key} SHA-256 mismatch: expected {expected}, got {actual}")
    return paths


def pipeline_fingerprint(
    *,
    config: Mapping[str, Any],
    separator: str,
    model_server_root: Path,
    compute_device: str = "auto",
) -> tuple[str, dict[str, Any]]:
    if separator not in BACKENDS:
        raise ValueError(f"unsupported separator: {separator}")
    paths = _require_model_files(config)
    resolved_device = _resolve_device(compute_device)
    code_files = (
        model_server_root / "separation.py",
        model_server_root / "pitch_engine.py",
        model_server_root / "pitch_profile.py",
        model_server_root / "build_range_catalog.py",
        model_server_root / "timbre_recommender" / "bulk_catalog.py",
        model_server_root / "timbre_recommender" / "embedding.py",
        model_server_root / "timbre_recommender" / "contracts.py",
    )
    missing_code = [str(path) for path in code_files if not path.is_file()]
    if missing_code:
        raise FileNotFoundError(f"pipeline source files are missing: {missing_code}")
    details = {
        "schema_version": BUILD_SCHEMA_VERSION,
        "pipeline_version": BUILD_PIPELINE_VERSION,
        "separator": separator,
        "pitch_extraction_version": PITCH_EXTRACTION_VERSION,
        "runtime": _runtime_provenance(separator, resolved_device),
        "parameters": {
            "mix_channels": MIX_CHANNELS,
            "mix_sample_rate": MIX_SAMPLE_RATE,
            "embedding_batch_size": EMBED_BATCH_SIZE,
            "embedding_max_segments": EMBED_MAX_SEGMENTS,
            "embedding_min_segments": EMBED_MIN_SEGMENTS,
            "index_max_segments": INDEX_MAX_SEGMENTS,
            "pitch_return_frames": False,
            "profile_kind": "catalog_song",
        },
        "models": {
            "ecapa_checkpoint_sha256": sha256_file(paths["ecapa"]),
            "singer_byol_checkpoint_sha256": sha256_file(paths["singer_model"]),
            "singer_byol_hparams_sha256": sha256_file(paths["singer_hparams"]),
            "singer_byol_code_revision": OFFICIAL_SINGER_BYOL_REVISION,
            "contracts": {
                key: {
                    "dimension": contract.dimension,
                    "preprocessing": contract.preprocessing,
                }
                for key, contract in MODEL_CONTRACTS.items()
            },
        },
        "source_sha256": {
            path.relative_to(model_server_root).as_posix(): sha256_file(path)
            for path in code_files
        },
    }
    return _canonical_hash(details), details


def _output_paths(workspace: Path, track_id: str) -> dict[str, Path]:
    prefix = workspace / "tracks" / track_id
    return {
        "ecapa": Path(f"{prefix}.ecapa.npy"),
        "singer_byol": Path(f"{prefix}.singer_byol.npy"),
        "segments": Path(f"{prefix}.segments.json"),
        "pitch": Path(f"{prefix}.pitch.json"),
    }


def _state_path(workspace: Path, track_id: str) -> Path:
    return workspace / "state" / f"{track_id}.json"


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def validate_checkpoint(
    track: SelectionTrack, workspace: Path, fingerprint: str
) -> tuple[bool, str]:
    state_path = _state_path(workspace, track.track_id)
    if not state_path.is_file():
        return False, "missing_state"
    try:
        state = _read_json(state_path)
        if state.get("status") != "complete":
            return False, f"state_{state.get('status', 'unknown')}"
        if state.get("audio_sha256") != track.audio_sha256:
            return False, "source_hash_changed"
        if state.get("pipeline_fingerprint") != fingerprint:
            return False, "pipeline_changed"
        outputs = _output_paths(workspace, track.track_id)
        records = state.get("outputs") or {}
        for key, path in outputs.items():
            if not path.is_file() or key not in records:
                return False, f"missing_{key}"
            if sha256_file(path) != records[key].get("sha256"):
                return False, f"changed_{key}"
        for key, contract in MODEL_CONTRACTS.items():
            values = np.load(outputs[key], mmap_mode="r", allow_pickle=False)
            if values.ndim != 2 or values.shape[1] != contract.dimension or len(values) == 0:
                return False, f"invalid_{key}_shape"
            if list(values.shape) != records[key].get("shape"):
                return False, f"changed_{key}_shape"
        profile = _read_json(outputs["pitch"])
        if profile.get("profileKind") != "catalog_song" or not profile.get("histogram"):
            return False, "invalid_pitch_profile"
        return True, "reusable"
    except (OSError, ValueError, KeyError, json.JSONDecodeError):
        return False, "invalid_checkpoint"


def pending_tracks(
    tracks: Iterable[SelectionTrack], workspace: Path, fingerprint: str
) -> tuple[list[SelectionTrack], dict[str, str]]:
    pending: list[SelectionTrack] = []
    reasons: dict[str, str] = {}
    for track in tracks:
        valid, reason = validate_checkpoint(track, workspace, fingerprint)
        reasons[track.track_id] = reason
        if not valid:
            pending.append(track)
    return pending, reasons


def _resolve_device(requested: str) -> str:
    import torch

    if requested == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is unavailable")
    return requested


def _convert_to_wav(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    command = [
        imageio_ffmpeg.get_ffmpeg_exe(),
        "-y",
        "-i",
        str(source),
        "-vn",
        "-ac",
        str(MIX_CHANNELS),
        "-ar",
        str(MIX_SAMPLE_RATE),
        str(destination),
    ]
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0 or not destination.is_file():
        raise RuntimeError(f"audio conversion failed: {completed.stderr[-600:]}")


def _offload_pitch_model() -> None:
    try:
        import torch
        import torchcrepe

        if hasattr(torchcrepe.infer, "model"):
            torchcrepe.infer.model.to("cpu")
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass


def _safe_remove_tree(path: Path, workspace: Path) -> None:
    resolved = path.resolve()
    root = workspace.resolve()
    if resolved == root or root not in resolved.parents:
        raise RuntimeError(f"refusing to remove path outside build workspace: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)


def _output_record(path: Path, *, include_shape: bool = False) -> dict[str, Any]:
    result: dict[str, Any] = {"filename": path.name, "sha256": sha256_file(path)}
    if include_shape:
        result["shape"] = list(np.load(path, mmap_mode="r", allow_pickle=False).shape)
    return result


def build_tracks(
    *,
    selection_path: Path,
    workspace: Path,
    config: Mapping[str, Any],
    model_server_root: Path,
    separator: str = "htdemucs",
    device: str = "auto",
    limit: int | None = None,
    continue_on_error: bool = False,
) -> dict[str, Any]:
    tracks = load_selection(selection_path)
    workspace = workspace.resolve()
    resolved_device = _resolve_device(device)
    fingerprint, fingerprint_details = pipeline_fingerprint(
        config=config,
        separator=separator,
        model_server_root=model_server_root.resolve(),
        compute_device=resolved_device,
    )
    pending, reasons = pending_tracks(tracks, workspace, fingerprint)
    reusable_count = len(tracks) - len(pending)
    original_pending_count = len(pending)
    if limit is not None:
        if limit <= 0:
            raise ValueError("limit must be positive")
        pending = pending[:limit]
    if not pending:
        return {
            "selected": len(tracks),
            "processed": 0,
            "reused": len(tracks),
            "failed": 0,
            "pipeline_fingerprint": fingerprint,
        }

    runtime = VocalEmbeddingRuntime(
        ecapa_checkpoint=config["ecapa_checkpoint"],
        singer_repo=config["singer_repo"],
        singer_checkpoint_root=config["singer_checkpoint_root"],
        device=resolved_device,
        batch_size=EMBED_BATCH_SIZE,
        offload_after_embed=True,
    )
    completed = 0
    failures: list[dict[str, str]] = []
    for position, track in enumerate(pending, 1):
        started = time.perf_counter()
        state_path = _state_path(workspace, track.track_id)
        temp_dir = workspace / "tmp" / track.track_id
        outputs = _output_paths(workspace, track.track_id)
        try:
            if not track.audio_path.is_file():
                raise FileNotFoundError(f"source audio not found: {track.audio_path}")
            actual_source_hash = sha256_file(track.audio_path)
            if actual_source_hash != track.audio_sha256:
                raise RuntimeError(
                    f"source SHA changed: expected {track.audio_sha256}, got {actual_source_hash}"
                )
            _safe_remove_tree(temp_dir, workspace)
            temp_dir.mkdir(parents=True, exist_ok=False)
            _atomic_write_json(
                state_path,
                {
                    "schema_version": BUILD_SCHEMA_VERSION,
                    "track_id": track.track_id,
                    "status": "processing",
                    "started_at": _now(),
                    "audio_sha256": track.audio_sha256,
                    "pipeline_fingerprint": fingerprint,
                    "resume_reason": reasons.get(track.track_id),
                },
            )
            mix_path = temp_dir / "mix.wav"
            _convert_to_wav(track.audio_path, mix_path)
            vocals, _, separation_info = separate(
                mix_path, temp_dir / "separation", resolved_device, separator
            )
            if separation_info.get("backend") != separator:
                raise RuntimeError(
                    f"separator domain changed: requested {separator}, got {separation_info}"
                )
            stem_hash = sha256_file(vocals)
            embed_vocal_audio(
                vocals,
                workspace / "tracks" / track.track_id,
                ecapa_checkpoint=config["ecapa_checkpoint"],
                singer_repo=config["singer_repo"],
                singer_checkpoint_root=config["singer_checkpoint_root"],
                device=resolved_device,
                batch_size=EMBED_BATCH_SIZE,
                max_segments=EMBED_MAX_SEGMENTS,
                min_segments=EMBED_MIN_SEGMENTS,
                force=True,
                runtime=runtime,
            )
            melody = extract_vocal_melody(
                vocals, None, resolved_device, return_frames=False
            )
            profile = summarize_pitch_profile(
                melody["notes"],
                profile_kind="catalog_song",
                f0_agreement_ratio=melody.get("f0AgreementRatio"),
                octave_mismatch_ratio=melody.get("octaveMismatchRatio"),
                compared_frames=melody.get("comparedFrames"),
            )
            profile["sourceSha256"] = stem_hash
            profile["pitchMethod"] = melody.get("pitchMethod")
            profile["separation"] = separation_info
            _atomic_write_json(outputs["pitch"], profile)
            output_records = {
                "ecapa": _output_record(outputs["ecapa"], include_shape=True),
                "singer_byol": _output_record(
                    outputs["singer_byol"], include_shape=True
                ),
                "segments": _output_record(outputs["segments"]),
                "pitch": _output_record(outputs["pitch"]),
            }
            state = {
                "schema_version": BUILD_SCHEMA_VERSION,
                "track_id": track.track_id,
                "status": "complete",
                "completed_at": _now(),
                "elapsed_seconds": round(time.perf_counter() - started, 2),
                "audio_sha256": track.audio_sha256,
                "vocal_stem_sha256": stem_hash,
                "pipeline_fingerprint": fingerprint,
                "pipeline": fingerprint_details,
                "separation": separation_info,
                "outputs": output_records,
            }
            _atomic_write_json(state_path, state)
            valid, reason = validate_checkpoint(track, workspace, fingerprint)
            if not valid:
                raise RuntimeError(f"completed checkpoint did not validate: {reason}")
            completed += 1
            print(
                f"[{position}/{len(pending)}] complete {track.track_id} "
                f"({state['elapsed_seconds']:.1f}s)",
                flush=True,
            )
        except Exception as error:
            _offload_pitch_model()
            failure = {
                "track_id": track.track_id,
                "error_type": type(error).__name__,
                "error": str(error)[:1000],
            }
            failures.append(failure)
            _atomic_write_json(
                state_path,
                {
                    "schema_version": BUILD_SCHEMA_VERSION,
                    "track_id": track.track_id,
                    "status": "failed",
                    "failed_at": _now(),
                    "elapsed_seconds": round(time.perf_counter() - started, 2),
                    "audio_sha256": track.audio_sha256,
                    "pipeline_fingerprint": fingerprint,
                    **failure,
                },
            )
            print(f"[{position}/{len(pending)}] failed {track.track_id}: {error}", flush=True)
            if not continue_on_error:
                raise
        finally:
            _offload_pitch_model()
            try:
                _safe_remove_tree(temp_dir, workspace)
            except OSError as cleanup_error:
                print(
                    f"warning: could not remove temporary files for {track.track_id}: "
                    f"{cleanup_error}",
                    flush=True,
                )
    return {
        "selected": len(tracks),
        "processed": completed,
        "reused": reusable_count,
        "deferred": original_pending_count - len(pending),
        "failed": len(failures),
        "failures": failures,
        "pipeline_fingerprint": fingerprint,
    }


def finalize_catalog(
    *,
    selection_path: Path,
    workspace: Path,
    config: Mapping[str, Any],
    model_server_root: Path,
    output_dir: Path,
    separator: str = "htdemucs",
    device: str = "auto",
    require_all: bool = True,
) -> dict[str, Any]:
    tracks = load_selection(selection_path)
    workspace = workspace.resolve()
    output_dir = output_dir.resolve()
    fingerprint, _ = pipeline_fingerprint(
        config=config,
        separator=separator,
        model_server_root=model_server_root.resolve(),
        compute_device=device,
    )
    complete: list[SelectionTrack] = []
    incomplete: dict[str, str] = {}
    for track in tracks:
        valid, reason = validate_checkpoint(track, workspace, fingerprint)
        if valid:
            complete.append(track)
        else:
            incomplete[track.track_id] = reason
    if incomplete and require_all:
        preview = dict(list(incomplete.items())[:20])
        raise RuntimeError(
            f"cannot finalize with {len(incomplete)} incomplete tracks; first items: {preview}"
        )
    if not complete:
        raise RuntimeError("no completed tracks are available to finalize")

    release_root = output_dir.parent
    release_root.mkdir(parents=True, exist_ok=True)
    manifest = release_root / "catalog.csv"
    temporary_manifest = release_root / ".catalog.csv.tmp"
    with temporary_manifest.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=(
                "track_id",
                "title",
                "artist",
                "ecapa_path",
                "singer_byol_path",
                "metadata_json",
            ),
        )
        writer.writeheader()
        for track in complete:
            outputs = _output_paths(workspace, track.track_id)
            writer.writerow(
                {
                    "track_id": track.track_id,
                    "title": track.title,
                    "artist": track.artist,
                    "ecapa_path": str(outputs["ecapa"].resolve()),
                    "singer_byol_path": str(outputs["singer_byol"].resolve()),
                    "metadata_json": json.dumps(
                        _safe_metadata(track.metadata),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ),
                }
            )
    os.replace(temporary_manifest, manifest)
    index_path = build_catalog_index(
        manifest, output_dir, max_segments=INDEX_MAX_SEGMENTS, force=True
    )
    profiles = {
        track.track_id: _read_json(_output_paths(workspace, track.track_id)["pitch"])
        for track in complete
    }
    write_range_catalog(
        index_path=index_path,
        output_path=output_dir / RANGE_CATALOG_FILENAME,
        profiles=profiles,
        source_hint=(
            f"ephemeral per-track {separator} stems; audio is not distributed"
        ),
    )
    catalog = CatalogIndex.load(output_dir, verify_hashes=True)
    ranges = RangeCatalog.load(
        output_dir,
        expected_track_ids={track.track_id for track in catalog.tracks},
        verify_hashes=True,
    )
    return {
        "tracks": len(catalog.tracks),
        "reliable_pitch_profiles": sum(
            bool(profile.get("reliable", True)) for profile in ranges.profiles.values()
        ),
        "incomplete_count": len(incomplete),
        "incomplete_preview": dict(list(incomplete.items())[:20]),
        "manifest": str(manifest),
        "output_dir": str(output_dir),
        "pipeline_fingerprint": fingerprint,
    }


def _validate_release_directory(directory: Path) -> set[str]:
    catalog = CatalogIndex.load(directory, verify_hashes=True)
    try:
        track_ids = {track.track_id for track in catalog.tracks}
        RangeCatalog.load(
            directory,
            expected_track_ids=track_ids,
            verify_hashes=True,
        )
        return track_ids
    finally:
        # CatalogIndex deliberately uses memory maps for serving.  Validation is
        # short-lived, so close them before a Windows directory rename/removal.
        for values in catalog.arrays.values():
            memory_map = getattr(values, "_mmap", None)
            if memory_map is not None:
                memory_map.close()


def publish_catalog(*, release_dir: Path, destination_dir: Path) -> dict[str, Any]:
    release_dir = release_dir.resolve()
    destination_dir = destination_dir.resolve()
    if release_dir == destination_dir:
        raise ValueError("release and destination directories must differ")
    source_track_ids = _validate_release_directory(release_dir)
    destination_parent = destination_dir.parent
    destination_parent.mkdir(parents=True, exist_ok=True)
    if destination_dir.exists() and not destination_dir.is_dir():
        raise NotADirectoryError(destination_dir)
    if destination_dir.exists():
        _validate_release_directory(destination_dir)
        existing_names = {path.name for path in destination_dir.iterdir()}
        unexpected = sorted(existing_names - set(MANAGED_RELEASE_FILES))
        if unexpected:
            raise RuntimeError(
                "refusing to replace a catalog directory containing unmanaged files: "
                f"{unexpected}"
            )

    token = f"{os.getpid()}-{time.time_ns()}"
    staging_dir = destination_parent / f".{destination_dir.name}.publish-{token}"
    backup_dir = destination_parent / f".{destination_dir.name}.publish-backup"
    if staging_dir.exists():
        raise FileExistsError("publish staging path already exists")
    if backup_dir.exists():
        if destination_dir.exists():
            _validate_release_directory(destination_dir)
            _safe_remove_tree(backup_dir, destination_parent)
        else:
            os.replace(backup_dir, destination_dir)

    moved_destination = False
    destination_existed = destination_dir.exists()
    try:
        staging_dir.mkdir()
        for name in MANAGED_RELEASE_FILES:
            source = release_dir / name
            staged = staging_dir / name
            shutil.copyfile(source, staged)
            if sha256_file(source) != sha256_file(staged):
                raise RuntimeError(f"publish copy verification failed: {name}")

        staged_track_ids = _validate_release_directory(staging_dir)
        if staged_track_ids != source_track_ids:
            raise RuntimeError("staged catalog track set changed during publish")

        if destination_dir.exists():
            os.replace(destination_dir, backup_dir)
            moved_destination = True
        try:
            os.replace(staging_dir, destination_dir)
        except BaseException:
            if moved_destination and not destination_dir.exists():
                os.replace(backup_dir, destination_dir)
                moved_destination = False
            raise

        published_track_ids = _validate_release_directory(destination_dir)
        if published_track_ids != source_track_ids:
            raise RuntimeError("published catalog track set changed during publish")
    except BaseException:
        if moved_destination and backup_dir.exists():
            failed_dir = destination_parent / f".{destination_dir.name}.publish-failed-{token}"
            if destination_dir.exists():
                os.replace(destination_dir, failed_dir)
            os.replace(backup_dir, destination_dir)
            moved_destination = False
            _safe_remove_tree(failed_dir, destination_parent)
        elif not destination_existed:
            _safe_remove_tree(destination_dir, destination_parent)
        _safe_remove_tree(staging_dir, destination_parent)
        raise
    else:
        _safe_remove_tree(backup_dir, destination_parent)
        return {"tracks": len(published_track_ids), "destination": str(destination_dir)}
