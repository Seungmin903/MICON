import csv
import io
import json

import boto3
import numpy as np
import pytest
from moto import mock_aws

from ai.embedding_catalog.contract import DIMENSIONS, digest, json_bytes
from ai.embedding_catalog.prepare import prepare, upload
from ai.embedding_catalog.storage import S3Store


@pytest.fixture
def models():
    return {
        "ecapa": {"dimension": 192, "version": "test-ecapa-v1", "checkpoint_sha256": "a" * 64,
                  "preprocessing": "mono 4 s -> per-window RMS 0.1 -> 16 kHz -> L2", "normalization": "l2"},
        "singer_byol": {"dimension": 1000, "version": "test-byol-v1", "checkpoint_sha256": "b" * 64,
                        "preprocessing": "mono 4 s at 44.1 kHz -> per-window peak normalize -> L2", "normalization": "l2"}
    }


@pytest.fixture
def store():
    with mock_aws():
        client = boto3.client("s3", region_name="ap-northeast-2")
        client.create_bucket(Bucket="micon-embedding-test",
                             CreateBucketConfiguration={"LocationConstraint": "ap-northeast-2"})
        yield S3Store("micon-embedding-test", "ap-northeast-2", client=client)


@pytest.fixture
def package_factory(tmp_path, models):
    def create(batch="test-v1", tracks=2):
        source = tmp_path / (batch + "-source")
        source.mkdir()
        csv_path = source / "tracks.csv"
        rows = []
        for index in range(tracks):
            values = {}
            for model, dimension in DIMENSIONS.items():
                rng = np.random.default_rng(index + dimension)
                array = rng.normal(size=(2, dimension)).astype("<f4")
                path = source / f"track-{index}.{model}.npy"
                np.save(path, array, allow_pickle=False)
                values[model + "_path"] = path.name
            metadata = {"models": models, "source_path": "C:/private/workstation/source.wav",
                        "segments": [{"start_seconds": 0.0, "end_seconds": 4.0},
                                     {"start_seconds": 2.0, "end_seconds": 6.0}]}
            metadata_path = source / f"track-{index}.segments.json"
            metadata_path.write_bytes(json_bytes(metadata))
            rows.append({"catalog_track_id": f"catalog-{index}", "title": f"곡 {index}", "artist": "테스트 가수",
                         "segments_path": metadata_path.name, **values})
        with csv_path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        output = tmp_path / batch
        path = prepare(csv_path, models, "tester", batch, output)
        data = path.read_bytes()
        return output, json.loads(data), digest(data)
    return create


def synthetic_snapshot(store, manifest, checksum, revision=1):
    return {"schema_version": 1, "channel": "default", "revision": revision,
            "batch_id": manifest["batch_id"], "bucket": store.bucket, "region": store.region,
            "manifest_key": manifest["prefix"] + "manifest.json", "manifest_sha256": checksum,
            "manifest_bytes": len(json_bytes(manifest)), "models": manifest["models"],
            "track_count": len(manifest["tracks"]),
            "tracks": [{"song_id": number + 1, "catalog_track_id": track["catalog_track_id"],
                        "embeddings": {name: {**record, "dimension": DIMENSIONS[name]}
                                       for name, record in track["embeddings"].items()}}
                       for number, track in enumerate(manifest["tracks"])]}
