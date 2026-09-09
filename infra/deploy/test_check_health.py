"""Local HTTP fixtures only. No Docker, external server, credentials, or database."""

import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import URLError

from check_health import check_once, validate_url, wait_for_ready


class Handler(BaseHTTPRequestHandler):
    status_code = 200
    content_type = "application/json"
    body = b'{"status":"UP"}'
    requests = 0

    def do_GET(self):
        type(self).requests += 1
        self.send_response(self.status_code)
        self.send_header("Content-Type", self.content_type)
        self.send_header("Location", "/redirected")
        self.end_headers()
        self.wfile.write(self.body)

    def log_message(self, *args):
        pass


class HealthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/actuator/health/readiness"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        Handler.status_code = 200
        Handler.content_type = "application/json"
        Handler.body = b'{"status":"UP"}'
        Handler.requests = 0

    def test_up(self):
        self.assertEqual(check_once(self.url, 1), (True, "ready"))

    def test_down(self):
        Handler.body = b'{"status":"DOWN"}'
        self.assertEqual(check_once(self.url, 1), (False, "not_up"))

    def test_edge_is_not_backend(self):
        Handler.body = b'{"status":"ok","scope":"nginx-only"}'
        self.assertFalse(check_once(self.url, 1)[0])

    def test_http_failure_even_with_up_body(self):
        Handler.status_code = 503
        self.assertEqual(check_once(self.url, 1), (False, "http_status"))

    def test_redirect_is_not_followed(self):
        Handler.status_code = 302
        self.assertFalse(check_once(self.url, 1)[0])
        self.assertEqual(Handler.requests, 1)

    def test_html_is_rejected(self):
        Handler.content_type = "text/html"
        self.assertEqual(check_once(self.url, 1), (False, "content_type"))

    def test_invalid_json(self):
        Handler.body = b'not JSON'
        self.assertEqual(check_once(self.url, 1), (False, "invalid_json"))

    def test_json_array_is_rejected(self):
        Handler.body = b'[{"status":"UP"}]'
        self.assertFalse(check_once(self.url, 1)[0])

    def test_large_body_is_rejected(self):
        Handler.body = b' ' * 65537
        self.assertEqual(check_once(self.url, 1), (False, "response_too_large"))

    def test_configuration_rejects_external_or_sensitive_urls(self):
        for url in ("http://example.com:80/actuator/health",
                    "http://127.0.0.1:8080/healthz",
                    "http://127.0.0.1:8080/actuator/health",
                    "http://user:secret@localhost:8080/actuator/health/readiness",
                    "http://localhost:8080/actuator/health/readiness?token=secret",
                    "http://localhost:8080/actuator/health/readiness#fragment",
                    "http://localhost/actuator/health/readiness",
                    "http://localhost:0/actuator/health/readiness",
                    "file:///actuator/health/readiness"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_url(url)

    def test_connection_failure_is_sanitized(self):
        with patch("check_health.build_opener") as opener:
            opener.return_value.open.side_effect = URLError("private diagnostic")
            self.assertEqual(check_once(self.url, 1), (False, "connection_or_timeout"))

    def test_socket_timeout_is_sanitized(self):
        with patch("check_health.build_opener") as opener:
            opener.return_value.open.side_effect = TimeoutError("private diagnostic")
            self.assertEqual(check_once(self.url, 1), (False, "connection_or_timeout"))

    def test_success_after_deadline_is_rejected(self):
        ticks = [0.0]

        def slow_probe(*_):
            ticks[0] = 4
            return True, "ready"

        self.assertFalse(wait_for_ready(
            self.url, deadline=3, interval=1, timeout=1, probe=slow_probe,
            required_successes=1, clock=lambda: ticks[0], report=lambda _: None))

    def test_retry_then_success(self):
        ticks = [0.0]
        outcomes = iter([
            (False, "not_up"),
            (True, "ready"),
            (True, "ready"),
            (True, "ready"),
        ])
        self.assertTrue(wait_for_ready(
            self.url, deadline=5, interval=1, timeout=1,
            probe=lambda *_: next(outcomes), clock=lambda: ticks[0],
            sleep=lambda seconds: ticks.__setitem__(0, ticks[0] + seconds),
            report=lambda _: None))
        self.assertEqual(ticks[0], 3)

    def test_failure_resets_success_streak(self):
        ticks = [0.0]
        outcomes = iter([
            (True, "ready"),
            (True, "ready"),
            (False, "not_up"),
            (True, "ready"),
            (True, "ready"),
            (True, "ready"),
        ])
        messages = []
        self.assertTrue(wait_for_ready(
            self.url, deadline=10, interval=1, timeout=1,
            probe=lambda *_: next(outcomes), clock=lambda: ticks[0],
            sleep=lambda seconds: ticks.__setitem__(0, ticks[0] + seconds),
            report=messages.append))
        self.assertIn("success_streak=0/3", messages[2])
        self.assertEqual(ticks[0], 5)

    def test_deadline_failure(self):
        ticks = [0.0]
        messages = []
        self.assertFalse(wait_for_ready(
            self.url, deadline=3, interval=1, timeout=1,
            probe=lambda *_: (False, "not_up"), clock=lambda: ticks[0],
            sleep=lambda seconds: ticks.__setitem__(0, ticks[0] + seconds),
            report=messages.append))
        self.assertEqual(ticks[0], 3)
        self.assertIn("keep_existing_route", messages[-1])

    def test_invalid_timing(self):
        for deadline in (-1, 0, 61, float("nan"), float("inf")):
            with self.subTest(deadline=deadline), self.assertRaises(ValueError):
                wait_for_ready(self.url, deadline=deadline)

    def test_invalid_required_successes(self):
        for count in (0, 11, 1.5, True):
            with self.subTest(count=count), self.assertRaises(ValueError):
                wait_for_ready(self.url, required_successes=count)


if __name__ == "__main__":
    unittest.main()
