"""Versioned, pickle-free on-disk catalog for segment embeddings."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
from typing import Any, Iterable

import numpy as np

from .contracts import INDEX_SCHEMA_VERSION, MODEL_CONTRACTS, MODEL_KEYS, SCORING_VERSION


INDEX_FILENAME = "index.json"
REQUIRED_MANIFEST_COLUMNS = {
    "track_id",
    "title",
    "artist",
    "ecapa_path",
    "singer_byol_path",
}
OPTIONAL_METADATA_COLUMN = "metadata_json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _atomic_save_npy(path: Path, values: np.ndarray) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    with temporary.open("wb") as stream:
        np.save(stream, values, allow_pickle=False)
    os.replace(temporary, path)


def _atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def _normalized_rows(values: np.ndarray, *, model_key: str, source: Path) -> np.ndarray:
    contract = MODEL_CONTRACTS[model_key]
    array = np.asarray(values, dtype=np.float32)
    if array.ndim == 1:
        array = array.reshape(1, -1)
    if array.ndim != 2 or array.shape[1] != contract.dimension:
        raise ValueError(
            f"{source}: {model_key} shape must be (segments, {contract.dimension}), "
            f"got {array.shape}"
        )
    if len(array) == 0 or not np.all(np.isfinite(array)):
        raise ValueError(f"{source}: {model_key} embeddings must be non-empty and finite")
    norms = np.linalg.norm(array, axis=1, keepdims=True)
    if np.any(norms <= 1e-12):
        bad = np.flatnonzero(norms[:, 0] <= 1e-12).tolist()
        raise ValueError(f"{source}: {model_key} has zero-norm rows {bad}")
    return np.ascontiguousarray(array / norms, dtype=np.float32)


def load_embedding(path: Path, model_key: str) -> np.ndarray:
    if not path.is_file():
        raise FileNotFoundError(f"missing {model_key} embedding: {path}")
    return _normalized_rows(
        np.load(path, allow_pickle=False), model_key=model_key, source=path
    )


def _uniformly_cap(values: np.ndarray, maximum: int) -> np.ndarray:
    if len(values) <= maximum:
        return values
    indices = np.linspace(0, len(values) - 1, num=maximum, dtype=np.int64)
    return np.ascontiguousarray(values[indices])


def _read_manifest(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        columns = set(reader.fieldnames or [])
        missing = sorted(REQUIRED_MANIFEST_COLUMNS - columns)
        if missing:
            raise ValueError(f"catalog manifest is missing columns: {missing}")
        rows = [{key: (value or "").strip() for key, value in row.items()} for row in reader]
    if not rows:
        raise ValueError("catalog manifest contains no tracks")
    return rows


def _parse_track_metadata(value: str, *, track_id: str) -> dict[str, Any]:
    if not value:
        return {}
    try:
        metadata = json.loads(value)
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid metadata_json for track {track_id}: {error}") from error
    if not isinstance(metadata, dict):
        raise ValueError(f"metadata_json for track {track_id} must be a JSON object")
    return metadata


def _resolve_manifest_path(manifest: Path, value: str, column: str) -> Path:
    if not value:
        raise ValueError(f"empty {column} in {manifest}")
    path = Path(value)
    if not path.is_absolute():
        path = manifest.parent / path
    return path.resolve()


def build_catalog_index(
    manifest_path: Path | str,
    output_dir: Path | str,
    *,
    max_segments: int = 9,
    force: bool = False,
) -> Path:
    """Build a compact catalog from per-track ECAPA and BYOL matrices.

    The manifest paths may be absolute or relative to the manifest. Both models
    are required for every track so that the absolute late fusion compares the
    same catalog entries.
    """

    if max_segments <= 0:
        raise ValueError("max_segments must be positive")
    manifest = Path(manifest_path).resolve()
    if not manifest.is_file():
        raise FileNotFoundError(f"catalog manifest not found: {manifest}")
    output = Path(output_dir).resolve()
    index_path = output / INDEX_FILENAME
    managed_paths = [index_path, *(output / f"{key}.npy" for key in MODEL_KEYS)]
    existing = [path for path in managed_paths if path.exists()]
    if existing and not force:
        raise FileExistsError(
            f"catalog index already exists ({existing[0]}); pass force=True to replace it"
        )
    output.mkdir(parents=True, exist_ok=True)

    rows = _read_manifest(manifest)
    seen: set[str] = set()
    track_records: list[dict[str, Any]] = []
    model_arrays: dict[str, list[np.ndarray]] = {key: [] for key in MODEL_KEYS}
    offsets = {key: 0 for key in MODEL_KEYS}

    for row_number, row in enumerate(rows, start=2):
        track_id = row["track_id"]
        if not track_id:
            raise ValueError(f"empty track_id at manifest row {row_number}")
        if track_id in seen:
            raise ValueError(f"duplicate track_id in catalog manifest: {track_id}")
        seen.add(track_id)
        if not row["title"] or not row["artist"]:
            raise ValueError(f"title and artist are required for track {track_id}")

        arrays: dict[str, Any] = {}
        sources: dict[str, Any] = {}
        for model_key in MODEL_KEYS:
            column = f"{model_key}_path"
            source = _resolve_manifest_path(manifest, row[column], column)
            values = _uniformly_cap(load_embedding(source, model_key), max_segments)
            source_array = np.load(source, mmap_mode="r", allow_pickle=False)
            original_segment_count = (
                int(source_array.shape[0]) if source_array.ndim == 2 else 1
            )
            count = len(values)
            arrays[model_key] = {"offset": offsets[model_key], "count": count}
            sources[model_key] = {
                "sha256": sha256_file(source),
                "original_segment_count": original_segment_count,
            }
            offsets[model_key] += count
            model_arrays[model_key].append(values)

        track_records.append(
            {
                "track_id": track_id,
                "title": row["title"],
                "artist": row["artist"],
                "metadata": _parse_track_metadata(
                    row.get(OPTIONAL_METADATA_COLUMN, ""), track_id=track_id
                ),
                "arrays": arrays,
                "sources": sources,
            }
        )

    file_records: dict[str, Any] = {}
    for model_key in MODEL_KEYS:
        destination = output / f"{model_key}.npy"
        combined = np.concatenate(model_arrays[model_key], axis=0)
        _atomic_save_npy(destination, combined)
        file_records[model_key] = {
            "filename": destination.name,
            "shape": list(combined.shape),
            "dtype": str(combined.dtype),
            "sha256": sha256_file(destination),
        }

    payload = {
        "schema_version": INDEX_SCHEMA_VERSION,
        "scoring_version": SCORING_VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "manifest_sha256": sha256_file(manifest),
        "max_segments_per_track": max_segments,
        "models": {
            key: {
                "display_name": contract.display_name,
                "dimension": contract.dimension,
                "input_sample_rate": contract.input_sample_rate,
                "window_seconds": contract.window_seconds,
                "preprocessing": contract.preprocessing,
            }
            for key, contract in MODEL_CONTRACTS.items()
        },
        "files": file_records,
        "tracks": track_records,
    }
    _atomic_write_json(index_path, payload)
    return index_path


@dataclass(frozen=True)
class Track:
    track_id: str
    title: str
    artist: str
    metadata: dict[str, Any]
    arrays: dict[str, dict[str, int]]


class CatalogIndex:
    """Read-only view of a built catalog index."""

    def __init__(self, root: Path, metadata: dict[str, Any], arrays: dict[str, np.ndarray]):
        self.root = root
        self.metadata = metadata
        self.arrays = arrays
        self.tracks = tuple(
            Track(
                track_id=item["track_id"],
                title=item["title"],
                artist=item["artist"],
                metadata=dict(item.get("metadata") or {}),
                arrays=item["arrays"],
            )
            for item in metadata["tracks"]
        )
        self._by_id = {track.track_id: track for track in self.tracks}

    @classmethod
    def load(cls, root: Path | str, *, verify_hashes: bool = False) -> "CatalogIndex":
        directory = Path(root).resolve()
        index_path = directory / INDEX_FILENAME
        if not index_path.is_file():
            raise FileNotFoundError(f"catalog index not found: {index_path}")
        metadata = json.loads(index_path.read_text(encoding="utf-8"))
        if metadata.get("schema_version") != INDEX_SCHEMA_VERSION:
            raise ValueError(
                f"unsupported index schema: {metadata.get('schema_version')} "
                f"(expected {INDEX_SCHEMA_VERSION})"
            )
        if metadata.get("scoring_version") != SCORING_VERSION:
            raise ValueError(f"unsupported scoring version: {metadata.get('scoring_version')}")

        arrays: dict[str, np.ndarray] = {}
        for model_key in MODEL_KEYS:
            model_metadata = metadata["models"][model_key]
            if int(model_metadata["dimension"]) != MODEL_CONTRACTS[model_key].dimension:
                raise ValueError(f"catalog model contract mismatch for {model_key}")
            record = metadata["files"][model_key]
            path = directory / record["filename"]
            if verify_hashes and sha256_file(path) != record["sha256"]:
                raise RuntimeError(f"catalog array hash mismatch: {path}")
            values = np.load(path, mmap_mode="r", allow_pickle=False)
            expected_shape = tuple(record["shape"])
            if values.shape != expected_shape or values.shape[1] != MODEL_CONTRACTS[model_key].dimension:
                raise ValueError(f"catalog array shape mismatch for {model_key}: {values.shape}")
            arrays[model_key] = values
        catalog = cls(directory, metadata, arrays)
        for track in catalog.tracks:
            for model_key in MODEL_KEYS:
                span = track.arrays[model_key]
                start = int(span["offset"])
                count = int(span["count"])
                if start < 0 or count <= 0 or start + count > len(arrays[model_key]):
                    raise ValueError(
                        f"invalid {model_key} array span for track {track.track_id}: {span}"
                    )
        return catalog

    def embeddings(self, track: Track | str, model_key: str) -> np.ndarray:
        item = self._by_id[track] if isinstance(track, str) else track
        span = item.arrays[model_key]
        start = int(span["offset"])
        end = start + int(span["count"])
        return np.asarray(self.arrays[model_key][start:end], dtype=np.float32)

    def select(self, excluded_track_ids: Iterable[str] = ()) -> tuple[Track, ...]:
        excluded = set(excluded_track_ids)
        unknown = excluded - self._by_id.keys()
        if unknown:
            raise ValueError(f"unknown excluded track_id values: {sorted(unknown)}")
        return tuple(track for track in self.tracks if track.track_id not in excluded)
