"""Local fixtures only; never contact MySQL, S3, or the deployment server."""

import hashlib
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from check_catalog_media import (
    CheckFailure,
    candidate_endpoint,
    request_candidate,
    validate_candidate_base,
    validate_prefix,
    validate_storage_url,
    verify_catalog_media,
)


OBJECT = b"OggS-local-test-opus"
OBJECT_SHA = hashlib.sha256(OBJECT).hexdigest()
OBJECT_KEY = "catalog/team/batch/1.opus"
STORAGE_URL = (
    "https://bucket.s3.ap-northeast-2.amazonaws.com/"
    "catalog/team/batch/1.opus?X-Amz-Expires=600&X-Amz-Signature=test"
)


class CandidateHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({
            "code": "SUCCESS",
            "message": "ok",
            "data": {
                "songId": 1,
                "selectedKey": 0,
                "instrumental": {
                    "mediaId": 1,
                    "filePath": OBJECT_KEY,
                    "mimeType": "audio/ogg",
                    "fileBytes": len(OBJECT),
                    "sha256": OBJECT_SHA,
                    "playbackUrl": STORAGE_URL,
                    "playbackUrlExpiresAt": "2026-09-16T00:10:00Z",
                },
                "lyricsSynced": "[00:00.00] test",
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
        cls.server = HTTPServer(("127.0.0.1", 0), CandidateHandler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def test_candidate_public_mr_contract(self):
        endpoint = candidate_endpoint(self.base_url, 1)
        self.assertTrue(endpoint.endswith("/songs/1/mr?selectedKey=0"))
        payload = request_candidate(endpoint, 1)
        self.assertEqual(payload["data"]["songId"], 1)

    def test_success_does_not_report_url_or_hash(self):
        messages = []
        self.assertTrue(verify_catalog_media(
            self.base_url,
            1,
            "bucket",
            "ap-northeast-2",
            "catalog/team/batch/",
            timeout=1,
            downloader=lambda *_: (len(OBJECT), OBJECT_SHA),
            report=messages.append,
        ))
        self.assertNotIn("amazonaws", messages[0])
        self.assertNotIn(OBJECT_SHA, messages[0])

    def test_uuid_and_legacy_string_ids_pass_the_same_gate(self):
        for song_id in ("e8873464-9cc0-5dbe-b3ac-f561fd55eaa5", "1"):
            with self.subTest(song_id=song_id):
                payload = request_candidate(candidate_endpoint(self.base_url, 1), 1)
                payload["data"]["songId"] = song_id
                self.assertIn(f"/songs/{song_id}/mr", candidate_endpoint(self.base_url, song_id))
                self.assertTrue(verify_catalog_media(
                    self.base_url, song_id, "bucket", "ap-northeast-2", "catalog/team/batch/",
                    candidate_request=lambda *_: payload,
                    downloader=lambda *_: (len(OBJECT), OBJECT_SHA), report=lambda _: None,
                ))

    def test_song_id_rejects_path_and_query_injection(self):
        for song_id in (True, False, 0, -1, "", "../other", "one/two", "one?key=2", "one#fragment", "x" * 65):
            with self.subTest(song_id=song_id), self.assertRaises(ValueError):
                candidate_endpoint(self.base_url, song_id)

    def test_hash_mismatch_fails_safely(self):
        with self.assertRaisesRegex(CheckFailure, "storage_sha256_mismatch"):
            verify_catalog_media(
                self.base_url,
                1,
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
            STORAGE_URL.replace("1.opus", "2.opus"),
            STORAGE_URL.split("?")[0],
            STORAGE_URL.replace("https://", "http://"),
        ):
            with self.subTest(url=url), self.assertRaises(CheckFailure):
                validate_storage_url(url, "bucket", "ap-northeast-2", OBJECT_KEY)


if __name__ == "__main__":
    unittest.main()
