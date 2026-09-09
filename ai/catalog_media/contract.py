from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re


SHA256 = re.compile(r"[0-9a-f]{64}")
CATALOG_ID = re.compile(r"[0-9a-f]{16}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_mapping(path: Path, expected_sha256: str, expected_count: int = 384) -> list[dict]:
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == expected_sha256.lower(), "Mapping SHA-256 mismatch")
    rows = []
    for number, raw in enumerate(data.decode("utf-8-sig").splitlines(), 1):
        try:
            row = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ValueError(f"Invalid mapping JSON on line {number}") from error
        require(isinstance(row, dict), f"Mapping line {number} must be an object")
        rows.append(row)
    require(len(rows) == expected_count, f"Expected {expected_count} mapping rows, got {len(rows)}")
    validate_rows(rows)
    return rows


def validate_rows(rows: list[dict]) -> None:
    require(bool(rows), "Mapping is empty")
    fields = {"bucket", "catalog_track_id", "content_type", "id_type", "media_type",
              "object_key", "relative_path", "schema_version", "sha256", "size_bytes", "song_id"}
    seen_catalog, seen_song, seen_key = set(), set(), set()
    for row in rows:
        require(set(row) == fields, "Mapping fields do not match schema version 1")
        track = row["catalog_track_id"]
        require(isinstance(track, str) and CATALOG_ID.fullmatch(track) is not None,
                "Invalid catalog_track_id")
        require(type(row["song_id"]) is int and row["song_id"] > 0, "Invalid song_id")
        require(row["bucket"] == "micon-b110-prod-apne2-20260909", "Unexpected bucket")
        require(row["schema_version"] == 1, "Unsupported mapping schema")
        require(row["id_type"] == "song_metadata.catalog_track_id", "Unexpected ID contract")
        require(row["media_type"] == "vocal_stem", "Unexpected media type")
        require(row["content_type"] == "audio/wav", "Unexpected content type")
        relative = f"vocals/{track}/vocals.wav"
        key = f"catalog/정영주/section-384-v1/{relative}"
        require(row["relative_path"] == relative and row["object_key"] == key,
                "Object key does not match its catalog ID")
        require(isinstance(row["sha256"], str) and SHA256.fullmatch(row["sha256"]) is not None,
                "Invalid object SHA-256")
        require(type(row["size_bytes"]) is int and 0 < row["size_bytes"] <= 512 * 1024 * 1024,
                "Invalid object size")
        require(track not in seen_catalog and row["song_id"] not in seen_song and key not in seen_key,
                "Duplicate catalog ID, song ID or object key")
        seen_catalog.add(track)
        seen_song.add(row["song_id"])
        seen_key.add(key)
