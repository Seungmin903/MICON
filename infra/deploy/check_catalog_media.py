"""Verify one public practice MR URL and its S3 object without logging secrets."""

import argparse
import hashlib
import ipaddress
import json
import re
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from check_health import NoRedirect


MAX_JSON_BYTES = 65536
MAX_MEDIA_BYTES = 1024 * 1024 * 1024
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


class CheckFailure(Exception):
    """Expected, safely reportable deployment-gate failure."""


def validate_candidate_base(url):
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
        loopback = host == "localhost" or ipaddress.ip_address(host).is_loopback
    except (ValueError, TypeError):
        raise ValueError("Use an explicit loopback candidate base URL") from None
    if (
        parsed.scheme not in ("http", "https")
        or not loopback
        or port is None or port == 0
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in ("", "/")
    ):
        raise ValueError("Use an explicit loopback candidate base URL")
    return url.rstrip("/")


def validate_prefix(prefix):
    if (
        not prefix
        or prefix.startswith("/")
        or not prefix.endswith("/")
        or "//" in prefix
        or any(part in ("", ".", "..") for part in prefix.split("/")[:-1])
    ):
        raise ValueError("Use a normalized non-root catalog prefix ending in slash")
    return prefix


def validate_storage_url(url, bucket, region, object_key):
    if not bucket or not region:
        raise ValueError("Expected bucket and region are required")
    try:
        parsed = urlsplit(url)
        query = parse_qs(parsed.query, keep_blank_values=True)
    except (ValueError, TypeError):
        raise CheckFailure("invalid_storage_url") from None
    expected_host = f"{bucket}.s3.{region}.amazonaws.com"
    if (
        parsed.scheme != "https"
        or parsed.hostname != expected_host
        or parsed.port is not None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or unquote(parsed.path.lstrip("/")) != object_key
        or len(query.get("X-Amz-Signature", [])) != 1
        or len(query.get("X-Amz-Expires", [])) != 1
    ):
        raise CheckFailure("invalid_storage_url")
    return url


def candidate_endpoint(base_url, song_id):
    if not isinstance(song_id, int) or isinstance(song_id, bool) or song_id < 1:
        raise ValueError("Song ID must be a positive integer")
    return f"{validate_candidate_base(base_url)}/songs/{song_id}/mr?selectedKey=0"


def request_candidate(endpoint, timeout):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(endpoint, headers={"Accept": "application/json"})
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status != 200:
                raise CheckFailure("candidate_http_status")
            if response.headers.get_content_type() != "application/json":
                raise CheckFailure("candidate_content_type")
            if "no-store" not in response.headers.get("Cache-Control", "").lower():
                raise CheckFailure("candidate_cache_control")
            raw = response.read(MAX_JSON_BYTES + 1)
            if len(raw) > MAX_JSON_BYTES:
                raise CheckFailure("candidate_response_too_large")
            return json.loads(raw)
    except HTTPError as error:
        error.close()
        raise CheckFailure("candidate_http_status") from None
    except (URLError, OSError, TimeoutError):
        raise CheckFailure("candidate_connection_or_timeout") from None
    except (ValueError, UnicodeError):
        raise CheckFailure("candidate_invalid_json") from None


def download_and_hash(url, expected_bytes, timeout):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(url, headers={"Accept": "*/*"})
    digest = hashlib.sha256()
    total = 0
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status != 200:
                raise CheckFailure("storage_http_status")
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > expected_bytes or total > MAX_MEDIA_BYTES:
                    raise CheckFailure("storage_size_mismatch")
                digest.update(chunk)
    except HTTPError as error:
        error.close()
        raise CheckFailure("storage_http_status") from None
    except (URLError, OSError, TimeoutError):
        raise CheckFailure("storage_connection_or_timeout") from None
    return total, digest.hexdigest()


def verify_catalog_media(base_url, song_id, bucket, region, allowed_prefix,
                         timeout=30, *, candidate_request=request_candidate,
                         downloader=download_and_hash, report=print):
    if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or not 0 < timeout <= 300:
        raise ValueError("Timeout must be between 0 and 300 seconds")
    allowed_prefix = validate_prefix(allowed_prefix)
    endpoint = candidate_endpoint(base_url, song_id)
    payload = candidate_request(endpoint, timeout)
    if not isinstance(payload, dict) or payload.get("code") != "SUCCESS":
        raise CheckFailure("candidate_contract")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise CheckFailure("candidate_contract")

    media = data.get("instrumental")
    if not isinstance(media, dict):
        raise CheckFailure("candidate_contract")
    object_key = media.get("filePath")
    expected_bytes = media.get("fileBytes")
    expected_sha = media.get("sha256")
    if (
        data.get("songId") != song_id
        or data.get("selectedKey") != 0
        or media.get("mimeType") != "audio/ogg"
        or not isinstance(object_key, str)
        or not object_key.startswith(allowed_prefix)
        or not object_key.removeprefix(allowed_prefix)
        or "\\" in object_key
        or any(part in ("", ".", "..") for part in object_key.split("/"))
        or not isinstance(expected_bytes, int)
        or isinstance(expected_bytes, bool)
        or not 0 < expected_bytes <= MAX_MEDIA_BYTES
        or not isinstance(expected_sha, str)
        or SHA256_PATTERN.fullmatch(expected_sha) is None
    ):
        raise CheckFailure("candidate_contract")

    storage_url = validate_storage_url(media.get("playbackUrl"), bucket, region, object_key)
    actual_bytes, actual_sha = downloader(storage_url, expected_bytes, timeout)
    if actual_bytes != expected_bytes:
        raise CheckFailure("storage_size_mismatch")
    if actual_sha != expected_sha:
        raise CheckFailure("storage_sha256_mismatch")
    report(
        f"catalog_media result=ready media=instrumental song_id={song_id} bytes={actual_bytes} "
        "sha256_match=true"
    )
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--song-id", required=True, type=int)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--allowed-prefix", required=True)
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()
    try:
        verify_catalog_media(
            args.base_url,
            args.song_id,
            args.bucket,
            args.region,
            args.allowed_prefix,
            args.timeout,
        )
        return 0
    except ValueError:
        print("catalog_media result=invalid_configuration")
        return 2
    except CheckFailure as error:
        print(f"catalog_media result=failed reason={error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
