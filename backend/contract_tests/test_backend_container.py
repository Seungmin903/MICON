"""Static safety contract for the backend image and production Compose file."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BackendContainerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dockerfile = (ROOT / "backend/Dockerfile").read_text(encoding="utf-8")
        cls.entrypoint = (ROOT / "backend/docker-entrypoint.sh").read_text(encoding="utf-8")
        cls.compose = (ROOT / "infra/compose/backend.compose.yml").read_text(encoding="utf-8")
        cls.production = (ROOT / "backend/src/main/resources/application-prod.properties").read_text(encoding="utf-8")
        cls.security = (ROOT / "backend/src/main/java/com/micon/backend/auth/config/SecurityConfig.java").read_text(encoding="utf-8")

    def test_image_is_pinned_and_runs_as_non_root(self):
        self.assertIn("@sha256:", self.dockerfile)
        self.assertIn("USER 10001:10001", self.dockerfile)
        self.assertIn('ENTRYPOINT ["/app/docker-entrypoint.sh"]', self.dockerfile)

    def test_secrets_are_files_and_not_tracked_values(self):
        for name in ("DB_PASSWORD", "JWT_SECRET", "AI_SERVICE_TOKEN"):
            self.assertIn(f"{name}_FILE", self.compose)
            self.assertNotIn(f"{name}=", self.compose)
        self.assertIn("AWS_SHARED_CREDENTIALS_FILE: /run/secrets/aws_credentials", self.compose)
        self.assertIn("load_secret DB_PASSWORD required", self.entrypoint)
        self.assertGreaterEqual(self.compose.count('uid: "10001"'), 4)
        self.assertGreaterEqual(self.compose.count("mode: 0400"), 4)

    def test_database_is_internal_but_backend_has_egress(self):
        self.assertIn("name: micon_backend", self.compose)
        self.assertIn("external: true", self.compose)
        self.assertIn("- egress", self.compose)
        self.assertIn("127.0.0.1:${MICON_BACKEND_BIND_PORT", self.compose)

    def test_migration_must_finish_before_backend_starts(self):
        self.assertIn("condition: service_completed_successfully", self.compose)
        self.assertIn('SPRING_FLYWAY_ENABLED: "false"', self.compose)
        self.assertIn("mysql_migration_password", self.compose)
        self.assertIn("../../backend/src/main/resources/db/migration:/flyway/sql:ro", self.compose)

    def test_container_is_hardened_and_has_readiness_healthcheck(self):
        self.assertGreaterEqual(self.compose.count("read_only: true"), 2)
        self.assertGreaterEqual(self.compose.count("no-new-privileges:true"), 2)
        self.assertGreaterEqual(self.compose.count("- ALL"), 2)
        self.assertIn("/actuator/health/readiness", self.compose)

    def test_probes_are_public_but_do_not_expose_details(self):
        self.assertIn("management.endpoint.health.group.readiness.include=readinessState,db", self.production)
        self.assertIn("management.endpoint.health.group.liveness.include=livenessState", self.production)
        self.assertIn("management.endpoint.health.show-details=never", self.production)
        self.assertIn('"/actuator/health/**"', self.security)


if __name__ == "__main__":
    unittest.main()
