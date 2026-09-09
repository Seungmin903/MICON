"""Local fixtures only; never contact MySQL, S3, or the deployment server."""

import hashlib
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from check_catalog_media import (
    CheckFailure,
    candidate_endpoint,
    check_unauthorized,
    request_candidate,
    validate_candidate_base,
    validate_prefix,
    validate_storage_url,
    verify_catalog_media,
)


OBJECT = b"RIFF-local-test-wave"
OBJECT_SHA = hashlib.sha256(OBJECT).hexdigest()
OBJECT_KEY = "catalog/team/batch/track-1/vocals.wav"
STORAGE_URL = (
    "https://bucket.s3.ap-northeast-2.amazonaws.com/"
    "catalog/team/batch/track-1/vocals.wav?X-Amz-Expires=600&X-Amz-Signature=test"
)


class CandidateHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer local-token":
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            return
        body = json.dumps({
            "code": "SUCCESS",
            "message": "ok",
            "data": {
                "mediaId": 1,
                "songId": 1,
                "catalogTrackId": "track-1",
                "mediaType": "VOCAL_STEM",
                "objectKey": OBJECT_KEY,
                "contentType": "audio/wav",
                "fileBytes": len(OBJECT),
                "sha256": OBJECT_SHA,
                "contentUrl": STORAGE_URL,
            },
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


class CatalogMediaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), CandidateHandler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.token_file = Path(self.temp.name) / "token"
        self.token_file.write_text("local-token\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_candidate_auth_and_json_contract(self):
        endpoint = candidate_endpoint(self.base_url, 1)
        check_unauthorized(endpoint, 1)
        payload = request_candidate(endpoint, "local-token", 1)
        self.assertEqual(payload["data"]["songId"], 1)

    def test_success_does_not_report_url_or_hash(self):
        messages = []
        self.assertTrue(verify_catalog_media(
            self.base_url,
            1,
            self.token_file,
            "bucket",
            "ap-northeast-2",
            "catalog/team/batch/",
            timeout=1,
            downloader=lambda *_: (len(OBJECT), OBJECT_SHA),
            report=messages.append,
        ))
        self.assertNotIn("amazonaws", messages[0])
        self.assertNotIn(OBJECT_SHA, messages[0])

    def test_hash_mismatch_fails_safely(self):
        with self.assertRaisesRegex(CheckFailure, "storage_sha256_mismatch"):
            verify_catalog_media(
                self.base_url,
                1,
                self.token_file,
                "bucket",
                "ap-northeast-2",
                "catalog/team/batch/",
                timeout=1,
                downloader=lambda *_: (len(OBJECT), "0" * 64),
                report=lambda _: None,
            )

    def test_external_candidate_urls_are_rejected(self):
        for url in (
            "https://example.com:443",
            "http://user:secret@127.0.0.1:8080",
            "http://127.0.0.1:8080/path",
            "http://127.0.0.1:8080?token=secret",
            "http://127.0.0.1",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_candidate_base(url)

    def test_prefix_traversal_is_rejected(self):
        for prefix in ("", "/catalog/", "catalog", "catalog//team/", "catalog/../team/"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                validate_prefix(prefix)

    def test_storage_url_must_match_bucket_region_key_and_signature(self):
        self.assertEqual(
            validate_storage_url(STORAGE_URL, "bucket", "ap-northeast-2", OBJECT_KEY),
            STORAGE_URL,
        )
        for url in (
            STORAGE_URL.replace("bucket.s3", "other.s3"),
            STORAGE_URL.replace("ap-northeast-2", "us-east-1"),
            STORAGE_URL.replace("track-1", "track-2"),
            STORAGE_URL.split("?")[0],
            STORAGE_URL.replace("https://", "http://"),
        ):
            with self.subTest(url=url), self.assertRaises(CheckFailure):
                validate_storage_url(url, "bucket", "ap-northeast-2", OBJECT_KEY)


if __name__ == "__main__":
    unittest.main()
