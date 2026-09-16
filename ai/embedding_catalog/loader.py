"""Checksum-verified, versioned cache with atomic activation and restart recovery."""
from __future__ import annotations

from contextlib import contextmanager
import csv
from datetime import datetime, timezone
from dataclasses import dataclass
import os
from pathlib import Path
import tempfile
import threading
import time
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .contract import (DIMENSIONS, MAX_MANIFEST_BYTES, artifacts, decode_array, digest, json_bytes,
                       parse_json, require, validate_models)
from .prepare import read_bounded
from .validation import fetch_manifest, fetch_track


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=".incoming-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


@contextmanager
def cache_lock(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".writer.lock").open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            lock = lambda: msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            unlock = lambda: msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            lock = lambda: fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            unlock = lambda: fcntl.flock(stream, fcntl.LOCK_UN)
        deadline = time.monotonic() + 10
        while True:
            try:
                lock()
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Another catalog cache writer is running")
                time.sleep(0.1)
        try:
            yield
        finally:
            stream.seek(0)
            unlock()


def validate_snapshot(snapshot: dict, store, models: dict) -> dict:
    require(isinstance(snapshot, dict) and snapshot.get("schema_version") == 1
            and snapshot.get("channel") == "default", "Invalid active catalog snapshot")
    require(type(snapshot.get("revision")) is int and snapshot["revision"] > 0, "Invalid activation revision")
    require(snapshot.get("bucket") == store.bucket and snapshot.get("region") == store.region,
            "Snapshot does not match the configured S3 store")
    require(snapshot.get("models") == models, "Runtime model/checkpoint/preprocessing contract mismatch")
    require(isinstance(snapshot.get("manifest_sha256"), str)
            and len(snapshot["manifest_sha256"]) == 64
            and all(c in "0123456789abcdef" for c in snapshot["manifest_sha256"]), "Invalid manifest checksum")
    require(type(snapshot.get("manifest_bytes")) is int
            and 0 < snapshot["manifest_bytes"] <= MAX_MANIFEST_BYTES, "Invalid manifest length")
    tracks = snapshot.get("tracks")
    require(isinstance(tracks, list) and len(tracks) == snapshot.get("track_count")
            and 0 < len(tracks) <= 5000, "Incomplete snapshot")
    ids, songs = set(), set()
    for track in tracks:
        require(isinstance(track, dict), "Invalid snapshot track")
        track_id, song_id = track.get("catalog_track_id"), track.get("song_id")
        require(isinstance(track_id, str) and track_id not in ids
                and type(song_id) is int and song_id > 0 and song_id not in songs, "Invalid snapshot song mapping")
        require(isinstance(track.get("embeddings"), dict)
                and set(track["embeddings"]) == set(DIMENSIONS), "Incomplete snapshot embeddings")
        ids.add(track_id)
        songs.add(song_id)
    return snapshot


@dataclass(frozen=True)
class LoadedCatalog:
    snapshot: dict
    manifest: dict
    root: Path
    arrays: dict

    def embeddings(self, song_id: int, model: str):
        return self.arrays[song_id][model]


class CatalogCache:
    def __init__(self, store, directory: Path, models: dict):
        validate_models(models)
        self.store, self.directory, self.models = store, directory, models
        self._current = None
        self._lock = threading.RLock()

    @property
    def current(self) -> LoadedCatalog:
        require(self._current is not None, "No validated catalog is loaded")
        return self._current

    def restore(self) -> LoadedCatalog:
        """Restore the last complete local snapshot without contacting S3."""
        with self._lock, cache_lock(self.directory):
            snapshot = parse_json(read_bounded(self.directory / "current.json", 16 * 1024 * 1024))
            self._current = self._load(snapshot, download=False)
            return self.current

    def refresh(self, snapshot: dict) -> LoadedCatalog:
        """A failure raises, leaves current.json unchanged and retains current memory."""
        with self._lock, cache_lock(self.directory):
            validate_snapshot(snapshot, self.store, self.models)
            pointer = self.directory / "current.json"
            if pointer.exists():
                previous = parse_json(read_bounded(pointer, 16 * 1024 * 1024))
                require(snapshot["revision"] >= previous["revision"], "Stale activation snapshot")
                if snapshot["revision"] == previous["revision"]:
                    require(snapshot == previous, "The same revision must identify the same snapshot")
            candidate = self._load(snapshot, download=True)
            atomic_write(pointer, json_bytes(snapshot))
            self._current = candidate
            return candidate

    def _load(self, snapshot: dict, *, download: bool) -> LoadedCatalog:
        from .contract import validate_manifest, validate_segments
        validate_snapshot(snapshot, self.store, self.models)
        root = self.directory / snapshot["manifest_sha256"]
        manifest_path = root / "manifest.json"
        if download:
            manifest, data = fetch_manifest(self.store, snapshot["manifest_key"], snapshot["manifest_sha256"])
        else:
            data = read_bounded(manifest_path, MAX_MANIFEST_BYTES)
            require(digest(data) == snapshot["manifest_sha256"], "Cached manifest checksum mismatch")
            manifest = validate_manifest(parse_json(data))
        require(len(data) == snapshot["manifest_bytes"]
                and manifest["batch_id"] == snapshot["batch_id"] and manifest["models"] == self.models,
                "Snapshot and manifest disagree")
        mapping = {track["catalog_track_id"]: track for track in snapshot["tracks"]}
        require(set(mapping) == {track["catalog_track_id"] for track in manifest["tracks"]},
                "Manifest catalog IDs do not match the DB snapshot")
        arrays = {}
        for track in manifest["tracks"]:
            target = mapping[track["catalog_track_id"]]
            for model, record in track["embeddings"].items():
                expected = {**record, "dimension": DIMENSIONS[model]}
                require(target["embeddings"][model] == expected, "DB embedding metadata differs from S3 manifest")
            materialized = {}
            for name, record in artifacts(track):
                path = root / record["key"].removeprefix(manifest["prefix"])
                if path.is_file():
                    cached = read_bounded(path, record["bytes"])
                    if digest(cached) == record["sha256"] and len(cached) == record["bytes"]:
                        materialized[name] = cached
            if len(materialized) != 3:
                require(download, "Cached artifacts are missing or corrupt")
                materialized = fetch_track(self.store, track, self.models)
            arrays[target["song_id"]] = {}
            for name, record in artifacts(track):
                value = materialized[name]
                if name == "segments":
                    validate_segments(value, track["embeddings"]["ecapa"]["rows"], self.models)
                else:
                    array = decode_array(value, name, record["rows"])
                    array.setflags(write=False)
                    arrays[target["song_id"]][name] = array
                if download:
                    atomic_write(root / record["key"].removeprefix(manifest["prefix"]), value)
        if download:
            atomic_write(manifest_path, data)
        return LoadedCatalog(snapshot, manifest, root, arrays)


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the internal service credential to a redirect target.


def fetch_snapshot(url: str, token: str) -> dict:
    parsed = urlparse(url)
    require(parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1")),
            "Use HTTPS for the internal backend endpoint")
    require(parsed.username is None and parsed.password is None and not parsed.fragment
            and not parsed.query and parsed.path.endswith("/internal/embedding-catalog/active"),
            "Invalid internal backend URL")
    require(bool(token), "AI_SERVICE_TOKEN is required")
    request = Request(url, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    with build_opener(_NoRedirect()).open(request, timeout=30) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    require(len(data) <= 16 * 1024 * 1024, "Backend snapshot exceeds size limit")
    envelope = parse_json(data)
    require(envelope.get("code") == "SUCCESS" and isinstance(envelope.get("data"), dict),
            "Backend did not return a successful snapshot")
    return envelope["data"]


def build_recommender_index(catalog: LoadedCatalog, destination: Path) -> Path:
    """Adapter for the existing recommend-algo build_catalog_index entry point.

    Import the existing recommender from its deployed package; it is not copied or
    replaced here. Returned numeric track IDs are MySQL song IDs.
    """
    from timbre_recommender.catalog import build_catalog_index
    from timbre_recommender.contracts import MODEL_CONTRACTS
    for key, model in catalog.manifest["models"].items():
        contract = MODEL_CONTRACTS[key]
        require(contract.dimension == model["dimension"] and contract.preprocessing == model["preprocessing"],
                "Installed recommender has a different preprocessing contract")
    destination.mkdir(parents=True, exist_ok=False)
    csv_path = destination / "source-manifest.csv"
    mapping = {t["catalog_track_id"]: t["song_id"] for t in catalog.snapshot["tracks"]}
    with csv_path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=["track_id", "title", "artist", "ecapa_path",
                                                    "singer_byol_path", "metadata_json"])
        writer.writeheader()
        for track in catalog.manifest["tracks"]:
            row = {"track_id": str(mapping[track["catalog_track_id"]]), "title": track["title"], "artist": track["artist"],
                   "metadata_json": json_bytes({**track.get("metadata", {}),
                                                 "song_id": mapping[track["catalog_track_id"]],
                                                 "catalog_track_id": track["catalog_track_id"],
                                                 "batch_id": catalog.snapshot["batch_id"]}).decode()}
            for model in DIMENSIONS:
                row[model + "_path"] = str((catalog.root / track["embeddings"][model]["key"]
                                           .removeprefix(catalog.manifest["prefix"])).resolve())
            writer.writerow(row)
    index_path = build_catalog_index(csv_path, destination, max_segments=12)
    _write_range_catalog(catalog, destination, index_path, mapping)
    atomic_write(destination / "activation.json", json_bytes(catalog.snapshot))
    return index_path


def _write_range_catalog(catalog: LoadedCatalog, destination: Path, index_path: Path,
                         mapping: dict) -> None:
    """곡 음역 프로파일을 인덱스 옆에 쓴다. 이 파일이 없으면 추천 서버가 기동하다 멈춘다.

    출처는 스냅샷 track 의 range_profile 이다. BE 가 아직 싣지 않으면
    MICON_RANGE_PROFILES 가 가리키는 파일에서 읽는다(catalog_track_id 키).
    """
    from timbre_recommender.pitch_range import (KEY_SHIFT_BOUNDS, RANGE_CATALOG_FILENAME,
                                                RANGE_SCORING_VERSION, canonical_json_sha256)
    from pitch_profile import PITCH_EXTRACTION_VERSION

    fallback = {}
    source = os.environ.get("MICON_RANGE_PROFILES", "").strip()
    if source:
        payload = parse_json(Path(source).read_bytes())
        fallback = payload.get("profiles", payload)

    tracks = catalog.snapshot["tracks"]
    if not fallback and not any(t.get("range_profile") for t in tracks):
        return          # 음역 프로파일을 아직 아무도 주지 않았다. 서버 기동 시 누락으로 잡힌다

    profiles, missing = {}, []
    for track in tracks:
        track_id = track["catalog_track_id"]
        profile = track.get("range_profile") or fallback.get(track_id)
        if profile is None:
            missing.append(track_id)
            continue
        profiles[str(mapping[track_id])] = profile
    require(not missing, f"Range profile missing for {len(missing)} tracks: {sorted(missing)[:5]}")

    atomic_write(destination / RANGE_CATALOG_FILENAME, json_bytes({
        "schemaVersion": 1,
        "scoringVersion": RANGE_SCORING_VERSION,
        "pitchExtractionVersion": PITCH_EXTRACTION_VERSION,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "profileMethod": "VocalScope CREPE full + Viterbi + pYIN; duration-weighted note histogram",
        "keyShiftRange": list(KEY_SHIFT_BOUNDS),
        "source": {"indexFile": index_path.name,
                   "indexCanonicalSha256": canonical_json_sha256(index_path),
                   "batchId": catalog.snapshot["batch_id"]},
        "profiles": profiles,
    }))
