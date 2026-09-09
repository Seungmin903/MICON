"""Convert paired team NPY files and a deliberate ID mapping into a release."""
from __future__ import annotations

import csv
import io
from itertools import islice
from pathlib import Path

import numpy as np

from .contract import (DIMENSIONS, MAX_MANIFEST_BYTES, MAX_OBJECT_BYTES, MAX_TRACKS, artifacts, decode_array, digest,
                       json_bytes, parse_json, require, validate_manifest, validate_models, validate_segments)


def read_bounded(path: Path, maximum: int) -> bytes:
    require(path.is_file() and path.stat().st_size <= maximum, f"Missing or oversized input: {path.name}")
    with path.open("rb") as stream:
        result = stream.read(maximum + 1)
    require(len(result) <= maximum, "Input grew beyond its limit")
    return result


def prepare(csv_path: Path, models: dict, owner: str, batch_id: str, output: Path) -> Path:
    validate_models(models)
    # No title matching or row-position matching: the operator supplies catalog_track_id.
    with csv_path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        expected = {"catalog_track_id", "title", "artist", "ecapa_path", "singer_byol_path", "segments_path"}
        require(expected.issubset(set(reader.fieldnames or [])), "CSV columns are incomplete")
        rows = list(islice(reader, MAX_TRACKS + 1))
    require(0 < len(rows) <= MAX_TRACKS, "Invalid track count")
    manifest = {"schema_version": 1, "batch_id": batch_id,
                "prefix": f"catalog/{owner}/{batch_id}/", "models": models, "tracks": []}
    payloads = {}
    for row in rows:
        require(all(isinstance(row.get(field), str) and row[field].strip() for field in expected),
                "CSV contains an empty required field")
        track_id = row["catalog_track_id"].strip()
        stem = digest(track_id.encode("utf-8"))
        track = {"catalog_track_id": track_id, "title": row["title"], "artist": row["artist"], "embeddings": {}}
        arrays = {}
        for model in DIMENSIONS:
            source = Path(row[model + "_path"])
            source = source if source.is_absolute() else csv_path.parent / source
            arrays[model] = decode_array(read_bounded(source, MAX_OBJECT_BYTES), model, normalized=False)
        count = len(arrays["ecapa"])
        require(count == len(arrays["singer_byol"]), "Model windows are not paired")
        metadata_path = Path(row["segments_path"])
        metadata_path = metadata_path if metadata_path.is_absolute() else csv_path.parent / metadata_path
        source_metadata = parse_json(read_bounded(metadata_path, MAX_OBJECT_BYTES))
        require(isinstance(source_metadata, dict) and isinstance(source_metadata.get("models"), dict),
                "Source model metadata is missing")
        for model, expected_model in models.items():
            observed = source_metadata.get("models", {}).get(model, {})
            require(all(observed.get(field) == expected_model[field]
                        for field in ("dimension", "checkpoint_sha256", "preprocessing")),
                    f"Source provenance mismatch: {track_id}/{model}")
            if "code_revision" in expected_model:
                require(observed.get("checked_out_code_revision") == expected_model["code_revision"],
                        "Source code revision mismatch")
        # Only necessary provenance is published; workstation paths and device details are omitted.
        metadata = {"schema_version": 1, "models": models, "segments": source_metadata.get("segments")}
        metadata_bytes = json_bytes(metadata)
        validate_segments(metadata_bytes, count, models)
        key = manifest["prefix"] + f"embeddings/{stem}.segments.json"
        track["segments"] = {"key": key, "sha256": digest(metadata_bytes), "bytes": len(metadata_bytes)}
        payloads[key] = metadata_bytes
        for model, array in arrays.items():
            norms = np.linalg.norm(array.astype(np.float64), axis=1, keepdims=True)
            array = np.ascontiguousarray(array / norms, dtype="<f4")
            stream = io.BytesIO()
            np.save(stream, array, allow_pickle=False)
            data = stream.getvalue()
            key = manifest["prefix"] + f"embeddings/{stem}.{model}.npy"
            track["embeddings"][model] = {"key": key, "sha256": digest(data), "bytes": len(data), "rows": len(array)}
            payloads[key] = data
        manifest["tracks"].append(track)
    validate_manifest(manifest)
    manifest_bytes = json_bytes(manifest)
    require(len(manifest_bytes) <= MAX_MANIFEST_BYTES, "Manifest exceeds size limit")
    # Refuse overwrites; a failed preparation leaves no publishable manifest.
    output.mkdir(parents=True, exist_ok=False)
    for key, data in payloads.items():
        destination = output / key.removeprefix(manifest["prefix"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    destination = output / "manifest.json"
    destination.write_bytes(manifest_bytes)
    return destination


def upload(directory: Path, store, *, dry_run: bool = False) -> dict:
    from .contract import MAX_MANIFEST_BYTES
    manifest_bytes = read_bounded(directory / "manifest.json", MAX_MANIFEST_BYTES)
    manifest = validate_manifest(parse_json(manifest_bytes))
    entries = [record for track in manifest["tracks"] for _, record in artifacts(track)]
    # Validate every local byte before performing the first upload.
    validated = []
    root = directory.resolve()
    for record in entries:
        path = (directory / record["key"].removeprefix(manifest["prefix"])).resolve()
        require(path.is_relative_to(root), "Local artifact escapes upload directory")
        data = read_bounded(path, record["bytes"])
        require(len(data) == record["bytes"] and digest(data) == record["sha256"], "Local checksum mismatch")
        validated.append((record["key"], data))
    manifest_key = manifest["prefix"] + "manifest.json"
    if not dry_run:
        for key, data in validated:
            store.put_immutable(key, data)
        store.put_immutable(manifest_key, manifest_bytes)  # Completion marker, always last.
    return {"batch_id": manifest["batch_id"], "manifest_key": manifest_key,
            "manifest_sha256": digest(manifest_bytes), "objects": len(entries) + 1, "dry_run": dry_run}
