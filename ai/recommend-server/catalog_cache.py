"""Verified, single-version cache for the read-only recommendation catalogs."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import threading
import time
from typing import Any

from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.pitch_range import RANGE_CATALOG_FILENAME, RangeCatalog


@dataclass(frozen=True)
class CatalogSnapshot:
    key: tuple[Any, ...]
    catalog: CatalogIndex
    ranges: RangeCatalog
    timings: dict[str, float]


_LOCK = threading.Lock()
_SNAPSHOT: CatalogSnapshot | None = None


def _version_key(root: Path) -> tuple[Any, ...]:
    """Use immutable deployment paths and file identity to detect a new catalog.

    Catalog bundles are published into versioned directories and never edited in
    place. Including every file's inode, size, and nanosecond mtime also catches
    an atomic replacement at the configured path before reusing the old mmap.
    Hashes are fully verified on every cache miss.
    """
    directory = root.resolve(strict=True)
    files: list[tuple[Any, ...]] = []
    for path in sorted((item for item in directory.rglob("*") if item.is_file()), key=lambda item: item.as_posix()):
        stat = path.stat()
        files.append((
            path.relative_to(directory).as_posix(), stat.st_dev, stat.st_ino,
            stat.st_size, stat.st_mtime_ns,
        ))
    required = {"index.json", RANGE_CATALOG_FILENAME}
    if not required.issubset({item[0] for item in files}):
        missing = sorted(required - {item[0] for item in files})
        raise FileNotFoundError(f"catalog snapshot is missing {missing}")
    return (str(directory), tuple(files))


def load_catalogs(root: Path | str) -> tuple[CatalogIndex, RangeCatalog, dict[str, float]]:
    """Load and fully validate a catalog once per immutable on-disk version."""
    global _SNAPSHOT
    directory = Path(root)
    lock_started = time.perf_counter()
    with _LOCK:
        lock_wait_ms = (time.perf_counter() - lock_started) * 1000
        fingerprint_started = time.perf_counter()
        key = _version_key(directory)
        fingerprint_ms = (time.perf_counter() - fingerprint_started) * 1000
        if _SNAPSHOT is not None and _SNAPSHOT.key == key:
            return _SNAPSHOT.catalog, _SNAPSHOT.ranges, {
                "catalogCacheHit": True,
                "catalogCacheLockWaitMs": round(lock_wait_ms, 2),
                "catalogFingerprintMs": round(fingerprint_ms, 2),
                "catalogIndexLoadMs": 0.0,
                "catalogIndexVerifyMs": 0.0,
                "rangeCatalogLoadMs": 0.0,
                "rangeCatalogVerifyMs": 0.0,
            }

        index_timings: dict[str, float] = {}
        catalog = CatalogIndex.load(directory, verify_hashes=True, timings=index_timings)
        range_timings: dict[str, float] = {}
        ranges = RangeCatalog.load(
            directory,
            expected_track_ids={track.track_id for track in catalog.tracks},
            verify_hashes=True,
            timings=range_timings,
        )
        verify_after_started = time.perf_counter()
        if _version_key(directory) != key:
            raise RuntimeError("catalog files changed while the verified snapshot was loading")
        fingerprint_ms += (time.perf_counter() - verify_after_started) * 1000
        timings = {
            "catalogCacheHit": False,
            "catalogCacheLockWaitMs": round(lock_wait_ms, 2),
            "catalogFingerprintMs": round(fingerprint_ms, 2),
            "catalogIndexLoadMs": round(max(
                0.0, index_timings.get("totalMs", 0.0) - index_timings.get("hashVerifyMs", 0.0),
            ), 2),
            "catalogIndexVerifyMs": round(index_timings.get("hashVerifyMs", 0.0), 2),
            "rangeCatalogLoadMs": round(max(
                0.0, range_timings.get("totalMs", 0.0) - range_timings.get("hashVerifyMs", 0.0),
            ), 2),
            "rangeCatalogVerifyMs": round(range_timings.get("hashVerifyMs", 0.0), 2),
        }
        _SNAPSHOT = CatalogSnapshot(key, catalog, ranges, timings)
        return catalog, ranges, dict(timings)


def clear_cache() -> None:
    """Clear the process cache. Intended for isolated tests and maintenance."""
    global _SNAPSHOT
    with _LOCK:
        _SNAPSHOT = None
