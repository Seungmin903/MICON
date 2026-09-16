import copy
import json
from pathlib import Path

import numpy as np
import pytest

from ai.embedding_catalog.contract import digest, json_bytes
from ai.embedding_catalog.loader import (
    CatalogCache,
    LoadedCatalog,
    _write_range_catalog,
    fetch_snapshot,
)
from ai.embedding_catalog.prepare import upload
from conftest import synthetic_snapshot


def test_load_and_offline_restart(package_factory, store, models, tmp_path, monkeypatch):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    snapshot = synthetic_snapshot(store, manifest, checksum)
    cache = CatalogCache(store, tmp_path / "cache", models)
    loaded = cache.refresh(snapshot)
    assert loaded.embeddings(1, "ecapa").shape == (2, 192)
    assert loaded.embeddings(2, "singer_byol").shape == (2, 1000)
    assert not loaded.embeddings(1, "ecapa").flags.writeable
    monkeypatch.setattr(store, "get", lambda *args: (_ for _ in ()).throw(OSError("S3 offline")))
    restored = CatalogCache(store, tmp_path / "cache", models).restore()
    np.testing.assert_array_equal(restored.embeddings(1, "ecapa"), loaded.embeddings(1, "ecapa"))


def test_range_catalog_writer_uses_playable_key_bounds(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "recommend-server"))
    snapshot = {
        "batch_id": "test-v1",
        "tracks": [
            {
                "catalog_track_id": "catalog-1",
                "range_profile": {"schemaVersion": 1},
            }
        ],
    }
    catalog = LoadedCatalog(snapshot=snapshot, manifest={}, root=tmp_path, arrays={})
    index_path = tmp_path / "index.json"
    index_path.write_text('{"schema_version":1,"tracks":[]}', encoding="utf-8")

    _write_range_catalog(catalog, tmp_path, index_path, {"catalog-1": 1})

    payload = json.loads((tmp_path / "range_profiles.json").read_text(encoding="utf-8"))
    assert payload["keyShiftRange"] == [-6, 5]
    assert set(payload["profiles"]) == {"1"}


def test_bad_new_batch_preserves_previous_memory_and_pointer(package_factory, store, models, tmp_path):
    directory, manifest, checksum = package_factory("first")
    upload(directory, store)
    cache = CatalogCache(store, tmp_path / "cache", models)
    previous = cache.refresh(synthetic_snapshot(store, manifest, checksum))
    pointer = (tmp_path / "cache/current.json").read_bytes()
    directory2, manifest2, checksum2 = package_factory("second")
    upload(directory2, store)
    store.client.delete_object(Bucket=store.bucket, Key=manifest2["tracks"][1]["segments"]["key"])
    with pytest.raises(Exception):
        cache.refresh(synthetic_snapshot(store, manifest2, checksum2, revision=2))
    assert cache.current is previous
    assert (tmp_path / "cache/current.json").read_bytes() == pointer


def test_loader_supports_explicit_rollback_with_new_revision(package_factory, store, models, tmp_path):
    directory, manifest, checksum = package_factory("first")
    directory2, manifest2, checksum2 = package_factory("second")
    upload(directory, store)
    upload(directory2, store)
    cache = CatalogCache(store, tmp_path / "cache", models)
    cache.refresh(synthetic_snapshot(store, manifest, checksum))
    cache.refresh(synthetic_snapshot(store, manifest2, checksum2, revision=2))
    cache.refresh(synthetic_snapshot(store, manifest, checksum, revision=3))
    assert cache.current.snapshot["batch_id"] == "first"
    assert cache.current.snapshot["revision"] == 3


@pytest.mark.parametrize("field", ["models", "song_id", "manifest_sha256", "metadata"])
def test_snapshot_tampering_is_rejected(package_factory, store, models, tmp_path, field):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    snapshot = synthetic_snapshot(store, manifest, checksum)
    if field == "models":
        snapshot["models"] = copy.deepcopy(models)
        snapshot["models"]["ecapa"]["version"] = "unknown"
    elif field == "song_id":
        snapshot["tracks"][1]["song_id"] = snapshot["tracks"][0]["song_id"]
    elif field == "manifest_sha256":
        snapshot["manifest_sha256"] = "../escape"
    else:
        snapshot["tracks"][0]["embeddings"]["ecapa"]["sha256"] = "0" * 64
    with pytest.raises(ValueError):
        CatalogCache(store, tmp_path / "cache", models).refresh(snapshot)


def test_stale_or_changed_revision_cannot_replace_cache(package_factory, store, models, tmp_path):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    snapshot = synthetic_snapshot(store, manifest, checksum, revision=2)
    cache = CatalogCache(store, tmp_path / "cache", models)
    cache.refresh(snapshot)
    with pytest.raises(ValueError, match="Stale"):
        cache.refresh({**snapshot, "revision": 1})
    with pytest.raises(ValueError, match="same revision"):
        cache.refresh({**snapshot, "batch_id": "tampered"})


def test_offline_restore_detects_corruption(package_factory, store, models, tmp_path):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    cache = CatalogCache(store, tmp_path / "cache", models)
    catalog = cache.refresh(synthetic_snapshot(store, manifest, checksum))
    first = manifest["tracks"][0]["embeddings"]["ecapa"]["key"].removeprefix(manifest["prefix"])
    (catalog.root / first).write_bytes(b"broken")
    with pytest.raises(ValueError):
        CatalogCache(store, tmp_path / "cache", models).restore()


@pytest.mark.parametrize("url", ["http://remote.example/internal/embedding-catalog/active",
                                "https://user:pass@example.com/internal/embedding-catalog/active",
                                "https://example.com/internal/embedding-catalog/active?token=secret"])
def test_snapshot_fetch_rejects_unsafe_urls_before_network(url):
    with pytest.raises(ValueError):
        fetch_snapshot(url, "test-token")
