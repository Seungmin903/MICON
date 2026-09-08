from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = (ROOT / "infra/compose/mysql.compose.yml").read_text(encoding="utf-8")
INIT = (ROOT / "infra/mysql/init/10-create-app-user.sh").read_text(encoding="utf-8")


class MysqlRuntimePolicyTests(unittest.TestCase):
    def test_exact_lts_image_digest_is_pinned(self):
        self.assertIn("mysql:8.4.11@sha256:", COMPOSE)

    def test_mysql_has_no_published_ports(self):
        service = COMPOSE.split("volumes:\n", 1)[0]
        self.assertNotIn("ports:", service)

    def test_persistent_volume_and_internal_network_are_required(self):
        self.assertIn("micon_mysql_data", COMPOSE)
        self.assertIn("internal: true", COMPOSE)
        self.assertIn("restart: unless-stopped", COMPOSE)

    def test_secrets_are_files_not_values(self):
        self.assertIn("MYSQL_ROOT_PASSWORD_FILE", COMPOSE)
        self.assertIn("MYSQL_PASSWORD_FILE", COMPOSE)
        self.assertIn("MYSQL_APP_PASSWORD_FILE", COMPOSE)
        self.assertNotIn("MYSQL_ROOT_PASSWORD:", COMPOSE)

    def test_runtime_excludes_redis(self):
        self.assertNotIn("redis:", COMPOSE)

    def test_app_user_has_no_schema_change_privileges(self):
        self.assertIn("GRANT SELECT, INSERT, UPDATE, DELETE ON micon.*", INIT)
        self.assertNotIn("GRANT ALL", INIT)


if __name__ == "__main__":
    unittest.main()
