"""S3 access restricted to one configured bucket and catalog prefix."""
from __future__ import annotations

import base64
import hashlib
from typing import Protocol

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from .contract import require, safe_key


class ObjectStore(Protocol):
    bucket: str
    region: str
    def get(self, key: str, maximum: int) -> bytes: ...
    def put_immutable(self, key: str, data: bytes) -> bool: ...


class S3Store:
    def __init__(self, bucket: str, region: str, *, prefix: str = "catalog/", client=None):
        require(bool(bucket) and bool(region) and prefix.startswith("catalog/") and prefix.endswith("/"),
                "Bucket, region and catalog prefix are required")
        self.bucket, self.region, self.prefix = bucket, region, prefix
        self.client = client or boto3.client("s3", region_name=region,
            config=Config(connect_timeout=5, read_timeout=30, retries={"mode": "standard", "max_attempts": 3}))

    def get(self, key: str, maximum: int) -> bytes:
        safe_key(key, self.prefix)
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        stream = response["Body"]
        try:
            require(response["ContentLength"] <= maximum, "S3 object exceeds size limit")
            data = stream.read(maximum + 1)
            require(len(data) == response["ContentLength"] and len(data) <= maximum, "Incomplete or oversized S3 object")
            return data
        finally:
            stream.close()

    def put_immutable(self, key: str, data: bytes) -> bool:
        safe_key(key, self.prefix)
        try:
            self.client.put_object(Bucket=self.bucket, Key=key, Body=data, IfNoneMatch="*",
                ServerSideEncryption="AES256",
                ChecksumSHA256=base64.b64encode(hashlib.sha256(data).digest()).decode("ascii"),
                ContentType="application/json" if key.endswith(".json") else "application/octet-stream")
            return True
        except ClientError as error:
            if error.response["Error"]["Code"] not in ("PreconditionFailed", "ConditionalRequestConflict", "412", "409"):
                raise
            require(self.get(key, len(data)) == data, "Existing S3 object differs; use a new batch_id")
            return False
