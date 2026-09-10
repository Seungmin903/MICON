from __future__ import annotations

import boto3
from botocore.config import Config

from .contract import require


class CatalogMediaStore:
    def __init__(self, bucket: str, region: str, client=None):
        require(bucket == "micon-b110-prod-apne2-20260909", "Unexpected catalog-media bucket")
        require(region == "ap-northeast-2", "Unexpected catalog-media region")
        self.bucket = bucket
        self.region = region
        self.client = client or boto3.client("s3", region_name=region, config=Config(
            connect_timeout=5, read_timeout=30, retries={"mode": "standard", "max_attempts": 3}))

    def verify_heads(self, rows: list[dict]) -> None:
        for row in rows:
            response = self.client.head_object(Bucket=self.bucket, Key=row["object_key"])
            require(response["ContentLength"] == row["size_bytes"],
                    f"S3 size mismatch: {row['catalog_track_id']}")
            require(response.get("ServerSideEncryption") == "AES256",
                    f"S3 encryption mismatch: {row['catalog_track_id']}")
            content_type = response.get("ContentType", "").lower()
            require(content_type in ("audio/wav", "audio/x-wav", "application/octet-stream"),
                    f"S3 content type mismatch: {row['catalog_track_id']}")
