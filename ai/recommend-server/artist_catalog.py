"""Version-bound, local-only song -> canonical singer ID snapshot.

Display names stay in the local embedding index for lab visualizations. They are
not identities and must never be serialized by the AI HTTP API.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from typing import Any, Mapping

from timbre_recommender.catalog import CatalogIndex

ARTIST_CATALOG_FILENAME = "artist_ids.json"
SCHEMA_VERSION = 1


def _positive_id(value: Any) -> int:
    if type(value) is not int or not 1 <= value <= 9223372036854775807:
        raise ValueError("Canonical artist ID must be a positive BIGINT")
    return value


def _batch(catalog: CatalogIndex) -> str:
    batches = {track.metadata.get("batch_id") for track in catalog.tracks}
    if len(batches) != 1 or not isinstance(next(iter(batches)), str):
        raise ValueError("Artist snapshot requires one explicit catalog batch")
    return next(iter(batches))


@dataclass(frozen=True)
class ArtistCatalog:
    catalog_version: str
    source_revision: int
    by_track: dict[str, int]
    song_ids: dict[str, str]

    @classmethod
    def load(cls, catalog: CatalogIndex) -> "ArtistCatalog":
        body = json.loads((catalog.root / ARTIST_CATALOG_FILENAME).read_text(encoding="utf-8"))
        return cls.validate(body, catalog)

    @classmethod
    def validate(cls, body: Mapping[str, Any], catalog: CatalogIndex) -> "ArtistCatalog":
        if not isinstance(body, Mapping):
            raise ValueError("Artist snapshot must be an object")
        if body.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError("Unsupported artist snapshot schema")
        if body.get("catalogVersion") != _batch(catalog):
            raise ValueError("Artist snapshot batch mismatch")
        digest = hashlib.sha256((catalog.root / "index.json").read_bytes()).hexdigest()
        if body.get("indexSha256") != digest:
            raise ValueError("Artist snapshot index hash mismatch")
        revision = _positive_id(body.get("sourceRevision"))
        records = body.get("tracks")
        if not isinstance(records, list):
            raise ValueError("Artist snapshot tracks must be a list")
        known = {track.track_id: track for track in catalog.tracks}
        mapping, songs = {}, {}
        for record in records:
            if not isinstance(record, Mapping):
                raise ValueError("Artist snapshot track must be an object")
            track_id = record.get("trackId")
            if track_id not in known or track_id in mapping:
                raise ValueError("Unknown or duplicate artist snapshot track")
            # Current 898-song snapshot has exactly one MAIN singer per song.
            # Reject new collaboration policies rather than silently guessing.
            if record.get("role") != "MAIN":
                raise ValueError("Artist snapshot requires the agreed MAIN singer policy")
            song_id = record.get("songId")
            expected = str(known[track_id].metadata.get("song_id") or track_id)
            if not isinstance(song_id, str) or song_id != expected:
                raise ValueError("Artist snapshot song identity mismatch")
            mapping[track_id] = _positive_id(record.get("artistId"))
            songs[track_id] = song_id
        if set(mapping) != set(known):
            raise ValueError("Artist snapshot does not cover the complete catalog")
        return cls(body["catalogVersion"], revision, mapping, songs)

    def preferred_ids(self, names: Any, catalog: CatalogIndex) -> list[int]:
        wanted = set(names) if isinstance(names, (list, tuple, set)) else set()
        return sorted({self.by_track[t.track_id] for t in catalog.tracks if t.artist in wanted})


def make_snapshot(catalog: CatalogIndex, relations: list[dict[str, Any]], revision: int) -> dict[str, Any]:
    """Consume an exact DB export; do not infer IDs from display names."""
    by_song: dict[str, list[dict[str, Any]]] = {}
    for relation in relations:
        by_song.setdefault(relation["songId"], []).append(relation)
    expected_songs = {str(t.metadata.get("song_id") or t.track_id) for t in catalog.tracks}
    if set(by_song) != expected_songs:
        raise ValueError("DB export must match the exact catalog song set")
    records = []
    for track in catalog.tracks:
        song_id = str(track.metadata.get("song_id") or track.track_id)
        linked = by_song.get(song_id, [])
        if len(linked) != 1:
            raise ValueError("Missing or multiple singers require an explicit policy")
        relation = linked[0]
        records.append({"trackId": track.track_id, "songId": song_id,
                        "artistId": _positive_id(relation["artistId"]), "role": relation["role"]})
    body = {"schemaVersion": SCHEMA_VERSION, "catalogVersion": _batch(catalog),
            "sourceRevision": _positive_id(revision),
            "indexSha256": hashlib.sha256((catalog.root / "index.json").read_bytes()).hexdigest(),
            "tracks": records}
    ArtistCatalog.validate(body, catalog)
    return body


_NAME_KEYS = {"artist", "artistname", "artistnames", "singername", "singernames", "preferredartists"}


def public_response(value: Any, mapping: ArtistCatalog | None = None, catalog: CatalogIndex | None = None) -> Any:
    """Copy/project current AND restored responses; never expose name fields.

    Internal preferences and full request payloads are not public job state.
    Computation paths require a validated mapping; historical GETs can still
    expose their existing song IDs when the snapshot is temporarily unavailable.
    """
    if isinstance(value, list):
        return [public_response(item, mapping, catalog) for item in value]
    if not isinstance(value, Mapping):
        return value
    result = {}
    for key, item in value.items():
        normalized = key.replace("_", "").lower()
        is_name = ("artist" in normalized or "singer" in normalized) and normalized.endswith(("name", "names"))
        if normalized in _NAME_KEYS or is_name or key in {"payload", "callbackBody"}:
            if normalized == "preferredartists" and mapping is not None and catalog is not None:
                result["preferred_artist_ids"] = mapping.preferred_ids(item, catalog)
            continue
        result[key] = public_response(item, mapping, catalog)
    track_id = value.get("track_id")
    if isinstance(track_id, str):
        artist_id = mapping.by_track.get(track_id) if mapping is not None else None
        if artist_id is None and mapping is not None and isinstance(value.get("song_id"), str):
            # Historical track IDs can differ, but an exact canonical DB song
            # ID is safe. Never match titles, names, TJ numbers or numeric IDs.
            by_song = {mapping.song_ids[tid]: aid for tid, aid in mapping.by_track.items()}
            artist_id = by_song.get(value["song_id"])
        result["artist_ids"] = [artist_id] if artist_id is not None else []
    return result
