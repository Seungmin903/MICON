"""Fail-closed contract for the tailnet-only embedding catalog bridge."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class TailscaleCatalogServeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = (ROOT / "infra/tailscale/enable_backend_catalog_serve.sh").read_text(encoding="utf-8")
        cls.nginx = (ROOT / "infra/nginx/micon-https.conf").read_text(encoding="utf-8")

    def test_bridge_is_root_only_and_uses_the_existing_service_token(self):
        self.assertIn('if [ "$(id -u)" -ne 0 ]', self.script)
        self.assertIn("/etc/micon/secrets/ai_service_token", self.script)
        self.assertIn('"Authorization": f"Bearer {token}"', self.script)
        self.assertNotIn('Bearer $(cat', self.script)
        self.assertNotIn("set -x", self.script)

    def test_only_the_exact_catalog_path_is_reachable_on_loopback(self):
        self.assertIn("listen 127.0.0.1:18080;", self.nginx)
        self.assertIn("location = /internal/embedding-catalog/active", self.nginx)
        self.assertIn("location / {", self.nginx)
        self.assertIn("return 404;", self.nginx)

    def test_existing_unrelated_serve_configuration_is_not_replaced(self):
        self.assertIn("refusing to replace an unrelated Tailscale Serve configuration", self.script)
        self.assertIn('tailscale serve --bg --yes "$LOOPBACK_PORT"', self.script)
        self.assertIn("Tailscale Serve did not retain the catalog bridge target", self.script)


if __name__ == "__main__":
    unittest.main()
