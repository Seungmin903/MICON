import hashlib
import json

import boto3
from moto import mock_aws
import pytest

from ai.catalog_media.contract import read_mapping
from ai.catalog_media.storage import CatalogMediaStore


def row(track="0156ac758f1192ee", song_id=212, size=4):
    relative = f"vocals/{track}/vocals.wav"
    return {
        "bucket": "micon-b110-prod-apne2-20260909",
        "catalog_track_id": track,
        "content_type": "audio/wav",
        "id_type": "song_metadata.catalog_track_id",
        "media_type": "vocal_stem",
        "object_key": f"catalog/정영주/section-384-v1/{relative}",
        "relative_path": relative,
        "schema_version": 1,
        "sha256": "a" * 64,
        "size_bytes": size,
        "song_id": song_id,
    }


def write_mapping(path, rows):
    data = "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in rows).encode()
    path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def test_mapping_requires_pinned_hash_exact_ids_and_paths(tmp_path):
    path = tmp_path / "mapping.jsonl"
    checksum = write_mapping(path, [row()])
    assert read_mapping(path, checksum, expected_count=1)[0]["song_id"] == 212
    with pytest.raises(ValueError, match="SHA-256"):
        read_mapping(path, "0" * 64, expected_count=1)
    changed = row()
    changed["object_key"] = "catalog/other/file.wav"
    checksum = write_mapping(path, [changed])
    with pytest.raises(ValueError, match="Object key"):
        read_mapping(path, checksum, expected_count=1)


def test_s3_head_verifies_size_encryption_and_content_type():
    with mock_aws():
        client = boto3.client("s3", region_name="ap-northeast-2")
        bucket = "micon-b110-prod-apne2-20260909"
        client.create_bucket(Bucket=bucket,
                             CreateBucketConfiguration={"LocationConstraint": "ap-northeast-2"})
        item = row()
        client.put_object(Bucket=bucket, Key=item["object_key"], Body=b"test",
                          ContentType="audio/wav", ServerSideEncryption="AES256")
        CatalogMediaStore(bucket, "ap-northeast-2", client=client).verify_heads([item])
        item["size_bytes"] = 5
        with pytest.raises(ValueError, match="size mismatch"):
            CatalogMediaStore(bucket, "ap-northeast-2", client=client).verify_heads([item])
