import copy
import io

import numpy as np
import pytest
from botocore.exceptions import ClientError

from ai.embedding_catalog.contract import (decode_array, digest, json_bytes, parse_json,
                                           validate_manifest, validate_segments)
from ai.embedding_catalog.prepare import prepare, upload
from ai.embedding_catalog.validation import validate_remote


def test_prepare_upload_validate_round_trip(package_factory, store):
    directory, manifest, checksum = package_factory()
    result = upload(directory, store)
    assert result["objects"] == 7
    assert result["manifest_sha256"] == checksum
    remote, _ = validate_remote(store, result["manifest_key"], checksum)
    assert remote == manifest
    for track in manifest["tracks"]:
        metadata = store.get(track["segments"]["key"], 512 * 1024)
        assert b"private/workstation" not in metadata
    assert upload(directory, store) == result


@pytest.mark.parametrize("corruption", ["duplicate", "traversal", "wrong_dimension", "mismatched_rows"])
def test_rejects_invalid_manifest(package_factory, corruption):
    _, manifest, _ = package_factory()
    changed = copy.deepcopy(manifest)
    first = changed["tracks"][0]
    if corruption == "duplicate":
        changed["tracks"][1]["catalog_track_id"] = first["catalog_track_id"]
    elif corruption == "traversal":
        first["embeddings"]["ecapa"]["key"] = changed["prefix"] + "../secret.npy"
    elif corruption == "wrong_dimension":
        changed["models"]["ecapa"]["dimension"] = 193
    else:
        first["embeddings"]["ecapa"]["rows"] = 3
    with pytest.raises(ValueError):
        validate_manifest(changed)


@pytest.mark.parametrize("array", [np.zeros((2, 192), dtype="<f4"),
                                  np.full((2, 192), np.nan, dtype="<f4"),
                                  np.ones((2, 193), dtype="<f4"),
                                  np.ones((2, 192), dtype="<f8"),
                                  np.ones((10, 192), dtype="<f4")])
def test_rejects_invalid_arrays(array):
    stream = io.BytesIO()
    np.save(stream, array, allow_pickle=False)
    with pytest.raises(ValueError):
        decode_array(stream.getvalue(), "ecapa")


def test_rejects_huge_npy_header_before_allocating():
    stream = io.BytesIO()
    np.lib.format.write_array_header_1_0(stream, {"descr": "<f4", "fortran_order": False,
                                                 "shape": (1_000_000_000, 192)})
    with pytest.raises(ValueError, match="shape"):
        decode_array(stream.getvalue(), "ecapa")


def test_rejects_nonfinite_or_duplicate_json():
    for raw in (b'{"value": NaN}', b'{"value": 1, "value": 2}'):
        with pytest.raises(ValueError):
            parse_json(raw)


def test_rejects_wrong_segment_provenance(package_factory):
    directory, manifest, _ = package_factory()
    record = manifest["tracks"][0]["segments"]
    data = (directory / record["key"].removeprefix(manifest["prefix"])).read_bytes()
    changed = parse_json(data)
    changed["models"]["ecapa"]["checkpoint_sha256"] = "0" * 64
    with pytest.raises(ValueError, match="contract"):
        validate_segments(json_bytes(changed), 2, manifest["models"])


def test_never_overwrites_existing_object(store):
    key = "catalog/tester/test-v1/value.json"
    assert store.put_immutable(key, b"same")
    assert not store.put_immutable(key, b"same")
    with pytest.raises(ValueError):
        store.put_immutable(key, b"different")
    assert store.get(key, 100) == b"same"


def test_store_cannot_access_voice_recordings(store):
    with pytest.raises(ValueError, match="prefix"):
        store.get("voice-recordings/user.wav", 100)


def test_manifest_is_not_published_when_an_artifact_fails(package_factory, store, monkeypatch):
    directory, manifest, _ = package_factory()
    original = store.put_immutable
    calls = 0
    def fail_second(key, data):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("network interruption")
        return original(key, data)
    monkeypatch.setattr(store, "put_immutable", fail_second)
    with pytest.raises(OSError):
        upload(directory, store)
    with pytest.raises(ClientError) as missing:
        store.get(manifest["prefix"] + "manifest.json", 8 * 1024 * 1024)
    assert missing.value.response["Error"]["Code"] == "NoSuchKey"


def test_dry_run_requires_no_aws_identity(package_factory):
    directory, _, _ = package_factory()
    assert upload(directory, None, dry_run=True)["objects"] == 7


def test_remote_corruption_is_detected(package_factory, store):
    directory, manifest, checksum = package_factory()
    upload(directory, store)
    key = manifest["tracks"][0]["embeddings"]["ecapa"]["key"]
    store.client.put_object(Bucket=store.bucket, Key=key, Body=b"corrupt")
    with pytest.raises(ValueError, match="checksum"):
        validate_remote(store, manifest["prefix"] + "manifest.json", checksum)


def test_large_finite_input_is_normalized_without_overflow(package_factory, models, tmp_path):
    package_factory()
    source = tmp_path / "test-v1-source"
    np.save(source / "track-0.ecapa.npy", np.full((2, 192), 3e38, dtype="<f4"), allow_pickle=False)
    output = tmp_path / "large-finite"
    prepare(source / "tracks.csv", models, "tester", "large-finite", output)
    manifest = parse_json((output / "manifest.json").read_bytes())
    record = manifest["tracks"][0]["embeddings"]["ecapa"]
    decoded = decode_array((output / record["key"].removeprefix(manifest["prefix"])).read_bytes(), "ecapa")
    np.testing.assert_allclose(np.linalg.norm(decoded, axis=1), 1, atol=1e-5)


@pytest.mark.parametrize("value", [None, [], "not-a-track"])
def test_malformed_track_is_rejected_as_validation_error(package_factory, value):
    _, manifest, _ = package_factory()
    manifest["tracks"][0] = value
    with pytest.raises(ValueError, match="track"):
        validate_manifest(manifest)
