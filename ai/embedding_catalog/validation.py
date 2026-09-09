"""Full remote validation shared by registration, activation and cache loading."""
from .contract import (MAX_MANIFEST_BYTES, artifacts, decode_array, digest, parse_json,
                       require, validate_manifest, validate_segments)


def fetch_manifest(store, key: str, expected_sha256: str):
    data = store.get(key, MAX_MANIFEST_BYTES)
    require(digest(data) == expected_sha256, "Manifest SHA-256 mismatch")
    manifest = validate_manifest(parse_json(data))
    require(key == manifest["prefix"] + "manifest.json", "Manifest key and prefix disagree")
    return manifest, data


def fetch_track(store, track, models):
    result = {}
    for name, record in artifacts(track):
        data = store.get(record["key"], record["bytes"])
        require(len(data) == record["bytes"] and digest(data) == record["sha256"],
                f"Artifact checksum mismatch: {track['catalog_track_id']}/{name}")
        if name == "segments":
            validate_segments(data, track["embeddings"]["ecapa"]["rows"], models)
        else:
            decode_array(data, name, record["rows"])
        result[name] = data
    return result


def validate_remote(store, key, sha256):
    manifest, data = fetch_manifest(store, key, sha256)
    for track in manifest["tracks"]:
        fetch_track(store, track, manifest["models"])
    return manifest, data
