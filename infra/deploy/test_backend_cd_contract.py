"""Static safety contract for the separate backend deployment pipeline."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BackendCdContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipeline = (ROOT / "ci/Jenkinsfile.backend").read_text(encoding="utf-8")
        cls.deploy = (ROOT / "infra/deploy/deploy_backend_release.sh").read_text(encoding="utf-8")
        cls.install = (ROOT / "infra/deploy/install_backend_release.sh").read_text(encoding="utf-8")

    def test_pipeline_is_main_only_and_serial(self):
        self.assertIn("restricted to the main branch", self.pipeline)
        self.assertIn("refs/remotes/origin/main", self.pipeline)
        self.assertIn("does not match the reviewed origin/main revision", self.pipeline)
        self.assertIn("disableConcurrentBuilds()", self.pipeline)
        self.assertNotIn("abortPrevious: true", self.pipeline)

    def test_pipeline_uses_managed_ssh_and_known_hosts(self):
        self.assertIn("sshUserPrivateKey", self.pipeline)
        self.assertIn("micon-server-known-hosts", self.pipeline)
        self.assertIn("StrictHostKeyChecking=yes", self.pipeline)
        self.assertIn("/home/$SSH_USER/micon-releases/incoming/", self.pipeline)
        self.assertNotIn("StrictHostKeyChecking=no", self.pipeline)
        self.assertNotIn("J15B110T.pem", self.pipeline)

    def test_pipeline_archives_exact_sha_and_checks_checksum(self):
        self.assertIn("git archive --format=tar.gz", self.pipeline)
        self.assertIn("sha256sum", self.pipeline)
        self.assertIn("release archive checksum mismatch", self.install)
        self.assertIn("unsafe path", self.install)
        self.assertIn("superseded", self.install)
        self.assertNotIn("rm -rf", self.install)

    def test_deploy_uses_inactive_slot_and_all_gates_before_switch(self):
        self.assertIn("candidate_slot=green", self.deploy)
        self.assertIn("candidate_slot=blue", self.deploy)
        health = self.deploy.index("check_health.py")
        catalog = self.deploy.index("check_catalog_media.py")
        switch = self.deploy.index("switch_backend_slot.sh")
        self.assertLess(health, catalog)
        self.assertLess(catalog, switch)
        self.assertIn("backend-deploy.env", self.deploy)

    def test_failure_before_switch_removes_candidate_only(self):
        self.assertIn('route_switched" = false', self.deploy)
        self.assertIn('docker compose -f "$COMPOSE_FILE" down', self.deploy)
        self.assertNotIn("down -v", self.deploy)
        self.assertNotIn("docker system prune", self.deploy)

    def test_pipeline_does_not_publish_gitlab_status(self):
        for forbidden in ("updateGitlabCommitStatus", "gitlabCommitStatus", "PRIVATE-TOKEN"):
            self.assertNotIn(forbidden, self.pipeline)


if __name__ == "__main__":
    unittest.main()
