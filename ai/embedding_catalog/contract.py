"""Wire contract shared by preparation, registration and runtime loading."""
from __future__ import annotations

import hashlib
import io
import json
import re
from typing import Any

import numpy as np

DIMENSIONS = {"ecapa": 192, "singer_byol": 1000}
MAX_TRACKS = 5000
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_OBJECT_BYTES = 512 * 1024
SLUG = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
SHA256 = re.compile(r"[0-9a-f]{64}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def valid_song_id(value: Any) -> bool:
    """V28 opaque string IDs, plus numeric IDs for restoring pre-V28 caches."""
    if type(value) is int:
        return value > 0
    return (isinstance(value, str) and value.isascii() and 0 < len(value) <= 64
            and all(c.isalnum() or c in "-_" for c in value))


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                       allow_nan=False) + "\n").encode("utf-8")


def parse_json(data: bytes) -> Any:
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    def invalid(value):
        raise ValueError(f"Non-finite JSON number: {value}")
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=pairs, parse_constant=invalid)


def safe_key(key: str, prefix: str) -> None:
    require(isinstance(key, str) and key.startswith(prefix) and len(key.encode("utf-8")) <= 1024,
            "Object key is outside the assigned prefix or too long")
    require(not any(part in ("", ".", "..") for part in key.split("/"))
            and not any(c in key for c in ("\\", "%", "?", "#", ":"))
            and all(ord(c) >= 32 and ord(c) != 127 for c in key), "Unsafe object key")


def validate_models(models: dict) -> None:
    require(isinstance(models, dict) and set(models) == set(DIMENSIONS), "Both model contracts are required")
    for name, dimension in DIMENSIONS.items():
        model = models[name]
        require(isinstance(model, dict) and model.get("dimension") == dimension,
                f"Wrong dimension for {name}")
        for field in ("version", "preprocessing"):
            value = model.get(field)
            require(isinstance(value, str) and 0 < len(value) <= 500, f"Missing {name}.{field}")
        require(SHA256.fullmatch(str(model.get("checkpoint_sha256", ""))) is not None,
                f"Missing checkpoint SHA-256 for {name}")
        require(model.get("normalization") == "l2", f"{name} requires l2 normalization")


def validate_manifest(manifest: dict) -> dict:
    require(isinstance(manifest, dict) and manifest.get("schema_version") == 1, "Unsupported manifest schema")
    batch = manifest.get("batch_id")
    require(isinstance(batch, str) and SLUG.fullmatch(batch) is not None, "Invalid batch_id")
    prefix = manifest.get("prefix", "")
    require(isinstance(prefix, str) and len(prefix) <= 512, "Invalid prefix")
    parts = prefix.split("/")
    require(len(parts) == 4 and parts[0] == "catalog" and parts[1] and parts[2] == batch and parts[3] == "",
            "Prefix must be catalog/<owner>/<batch-id>/")
    safe_key(prefix + "manifest.json", prefix)
    validate_models(manifest.get("models"))
    tracks = manifest.get("tracks")
    require(isinstance(tracks, list) and 0 < len(tracks) <= MAX_TRACKS, "Invalid track count")
    seen, keys = set(), set()
    for track in tracks:
        require(isinstance(track, dict), "Invalid track entry")
        track_id = track.get("catalog_track_id")
        require(isinstance(track_id, str) and 0 < len(track_id) <= 100
                and track_id == track_id.strip() and all(ord(c) >= 32 for c in track_id),
                "Invalid catalog_track_id")
        require(track_id not in seen, f"Duplicate catalog_track_id: {track_id}")
        seen.add(track_id)
        require(isinstance(track.get("title"), str) and 0 < len(track["title"]) <= 255
                and isinstance(track.get("artist"), str) and 0 < len(track["artist"]) <= 255,
                "Track title and artist are required")
        require(isinstance(track.get("embeddings"), dict)
                and set(track["embeddings"]) == set(DIMENSIONS), "Both embeddings are required")
        require(isinstance(track.get("segments"), dict), "Segments reference is required")
        counts = []
        for name, record in artifacts(track):
            require(isinstance(record, dict), "Invalid artifact reference")
            safe_key(record.get("key", ""), prefix)
            require(record["key"] not in keys, "Repeated object key")
            keys.add(record["key"])
            require(SHA256.fullmatch(str(record.get("sha256", ""))) is not None, "Invalid artifact checksum")
            require(type(record.get("bytes")) is int and 0 < record["bytes"] <= MAX_OBJECT_BYTES,
                    "Invalid artifact size")
            if name != "segments":
                require(type(record.get("rows")) is int and 1 <= record["rows"] <= 9, "Expected 1..9 segments")
                require(record["key"].endswith(".npy"), "Embedding must be NPY")
                counts.append(record["rows"])
            else:
                require(record["key"].endswith(".json"), "Segments must be JSON")
        require(counts[0] == counts[1], "Both models must use identical segments")
    return manifest


def artifacts(track: dict):
    return [*(track["embeddings"].items()), ("segments", track["segments"])]


def decode_array(data: bytes, model: str, rows: int | None = None, *, normalized: bool = True) -> np.ndarray:
    require(len(data) <= MAX_OBJECT_BYTES, "Embedding exceeds size limit")
    # Parse the header BEFORE allocation. A tiny file can otherwise claim a huge shape.
    stream = io.BytesIO(data)
    version = np.lib.format.read_magic(stream)
    require(version in ((1, 0), (2, 0)), "Only NPY v1/v2 is supported")
    header_reader = np.lib.format.read_array_header_1_0 if version == (1, 0) else np.lib.format.read_array_header_2_0
    shape, fortran, dtype = header_reader(stream, max_header_size=4096)
    require(len(shape) == 2 and 1 <= shape[0] <= 9 and shape[1] == DIMENSIONS[model],
            f"Invalid {model} shape: {shape}")
    require(not fortran and dtype == np.dtype("<f4"), "Expected C-order little-endian float32")
    require(len(data) - stream.tell() == shape[0] * shape[1] * 4, "Truncated or trailing NPY data")
    array = np.frombuffer(data, dtype="<f4", offset=stream.tell()).reshape(shape).copy()
    require(rows is None or rows == len(array), "Segment count does not match manifest")
    require(np.isfinite(array).all(), "Non-finite embedding")
    norms = np.linalg.norm(array.astype(np.float64), axis=1)
    require(np.all(norms > 1e-12), "Zero embedding")
    if normalized:
        require(np.allclose(norms, 1.0, atol=1e-4), "Embedding rows must be L2 normalized")
    return array


def validate_segments(data: bytes, rows: int, models: dict) -> dict:
    metadata = parse_json(data)
    require(isinstance(metadata, dict) and metadata.get("models") == models, "Segment model contract mismatch")
    segments = metadata.get("segments")
    require(isinstance(segments, list) and len(segments) == rows, "Segment metadata count mismatch")
    for segment in segments:
        require(isinstance(segment, dict), "Invalid segment metadata")
        start, end = segment.get("start_seconds"), segment.get("end_seconds")
        require(type(start) in (int, float) and type(end) in (int, float)
                and np.isfinite([start, end]).all() and 0 <= start < end
                and abs(end - start - 4.0) <= 0.01, "Each segment must describe a 4-second window")
    return metadata
