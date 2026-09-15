"""Static fail-closed contract for the public Nginx backend route."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class NginxBackendRoutingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.nginx = (ROOT / "infra/nginx/micon-https.conf").read_text(encoding="utf-8")
        cls.switch = (ROOT / "infra/nginx/switch_backend_slot.sh").read_text(encoding="utf-8")
        cls.blue = (ROOT / "infra/nginx/backend-slots/blue.conf").read_text(encoding="utf-8")
        cls.green = (ROOT / "infra/nginx/backend-slots/green.conf").read_text(encoding="utf-8")
        cls.verify = (ROOT / "infra/nginx/verify_candidate_config.sh").read_text(encoding="utf-8")

    def test_public_prefix_is_stripped_once(self):
        self.assertIn("location /api/v1/", self.nginx)
        self.assertIn("upstream micon_backend_active", self.nginx)
        self.assertIn("include /etc/nginx/micon/backend-active.conf;", self.nginx)
        self.assertIn("proxy_pass http://micon_backend_active/;", self.nginx)
        self.assertIn("server 127.0.0.1:18081;", self.blue)
        self.assertIn("server 127.0.0.1:18082;", self.green)

    def test_ai_catalog_bridge_is_loopback_only_and_exact(self):
        self.assertIn("listen 127.0.0.1:18080;", self.nginx)
        self.assertIn("location = /internal/embedding-catalog/active", self.nginx)
        self.assertIn("proxy_pass http://micon_backend_active;", self.nginx)
        self.assertEqual(self.nginx.count("location = /internal/embedding-catalog/active"), 1)

    def test_management_and_internal_routes_are_publicly_blocked(self):
        for path in ("/actuator", "/internal", "/api/v1/actuator", "/api/v1/internal"):
            self.assertIn(f"location = {path}", self.nginx)
        self.assertIn("location ~ (^|/)\\.", self.nginx)

    def test_proxy_forwards_origin_and_has_bounded_timeouts(self):
        for header in (
            "Host $host",
            "X-Real-IP $remote_addr",
            "X-Forwarded-For $proxy_add_x_forwarded_for",
            "X-Forwarded-Proto $scheme",
        ):
            self.assertIn(f"proxy_set_header {header};", self.nginx)
        self.assertIn("proxy_connect_timeout 3s;", self.nginx)
        self.assertIn("proxy_read_timeout 130s;", self.nginx)
        self.assertIn("client_max_body_size 27m;", self.nginx)

    def test_switch_requires_readiness_before_filesystem_changes(self):
        health_position = self.switch.index("check_health.py")
        install_position = self.switch.index("install -d")
        self.assertLess(health_position, install_position)
        self.assertIn("--required-successes 3", self.switch)

    def test_switch_restores_route_on_nginx_or_public_smoke_failure(self):
        self.assertGreaterEqual(self.switch.count("restore_previous"), 4)
        self.assertGreaterEqual(self.switch.count("nginx -t"), 4)
        self.assertIn("systemctl reload nginx.service", self.switch)
        self.assertIn('if public_status="$(curl', self.switch)
        self.assertIn('"https://$DOMAIN/api/v1/users/me"', self.switch)
        self.assertNotIn('"https://$DOMAIN/api/v1/songs"', self.switch)
        self.assertIn("public API smoke failed; previous route restored", self.switch)
        self.assertIn('public_status" = 401', self.switch)
        self.assertIn('"code":"UNAUTHENTICATED"', self.switch)

    def test_switch_allows_bounded_nginx_reload_handover(self):
        reload_position = self.switch.index("systemctl reload nginx.service")
        retry_position = self.switch.index('while [ "$public_smoke_attempt" -le 10 ]')
        self.assertLess(reload_position, retry_position)
        self.assertIn("public_smoke_attempt=1", self.switch)
        self.assertIn("--max-time 3", self.switch)
        self.assertIn("sleep 1", self.switch)
        self.assertIn("public_smoke_attempt=$((public_smoke_attempt + 1))", self.switch)
        self.assertIn('if [ "$public_smoke_ready" != true ]', self.switch)

    def test_script_only_accepts_two_loopback_slots(self):
        self.assertIn("blue) port=18081", self.switch)
        self.assertIn("green) port=18082", self.switch)
        self.assertNotIn("0.0.0.0", self.nginx + self.switch + self.blue + self.green)

    def test_candidate_syntax_check_uses_isolated_files(self):
        self.assertIn("mktemp -d /tmp/micon-nginx-candidate.", self.verify)
        self.assertIn("nginx -t", self.verify)
        self.assertIn("server 127.0.0.1:18081;", self.verify)
        self.assertNotIn("systemctl", self.verify)
        self.assertNotIn("/etc/nginx/sites-available", self.verify)


if __name__ == "__main__":
    unittest.main()
