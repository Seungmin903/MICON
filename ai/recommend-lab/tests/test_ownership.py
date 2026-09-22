import json
import sys
import tempfile
import time
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ownership import OwnerResolver, SnapshotLookup, login_identifier, numeric_id, ownership_query
from repository import Repository
from fastapi.testclient import TestClient
from app import create_app


class OwnershipTest(unittest.TestCase):
    def row(self, **extra):
        return dict(id="job_owner_test", kind="analysis", sessionId=101, **extra)

    def owner(self, **extra):
        return dict(id="job_owner_test", kind="analysis", sessionId="101", userId="5", loginId="singer-test@example.test", source="backend-analysis-job", **extra)

    def test_direct_job_lookup_preserves_bigint_and_does_not_infer_session_owner(self):
        lookup = Mock(return_value=[self.owner()])
        rows = [self.row(), dict(id="job_other", kind="analysis", sessionId=101), dict(id="case_synthetic", kind="case")]
        result = OwnerResolver(lookup).enrich(rows)
        self.assertEqual(result[0]["userId"], "5")
        self.assertEqual(result[0]["loginId"], "singer-test@example.test")
        self.assertEqual(result[0]["loginIdStatus"], "verified")
        self.assertEqual(result[0]["ownerStatus"], "verified")
        self.assertIsNone(result[1]["userId"])
        self.assertIsNone(result[1]["loginId"])
        self.assertEqual(result[1]["ownerStatus"], "not-found")
        self.assertEqual(result[2]["ownerStatus"], "not-applicable")
        self.assertEqual(numeric_id("9223372036854775807"), "9223372036854775807")
        for invalid in [True, 1.0, -1, 0, "12 OR 1=1", "9223372036854775808"]:
            self.assertIsNone(numeric_id(invalid))

    def test_batch_cache_new_jobs_and_failure_never_keep_stale_identity(self):
        clock = Mock(return_value=0)
        lookup = Mock(return_value=[self.owner()])
        resolver = OwnerResolver(lookup, clock=clock)
        resolver.enrich([self.row()]); resolver.enrich([self.row()])
        self.assertEqual(lookup.call_count, 1)
        resolver.enrich([self.row(), dict(id="job_new", kind="analysis")])
        self.assertEqual(lookup.call_count, 2)
        self.assertEqual(len(lookup.call_args.args[0]), 1)
        clock.return_value = 31
        lookup.side_effect = OSError("private remote failure")
        result = resolver.enrich([self.row()])[0]
        self.assertIsNone(result["userId"])
        self.assertIsNone(result["loginId"])
        self.assertEqual(result["ownerStatus"], "unavailable")
        self.assertNotIn("private", json.dumps(result))
        resolver.enrich([self.row()])
        self.assertEqual(lookup.call_count, 3)

    def test_background_owner_lookup_never_blocks_request_list(self):
        started, release = threading.Event(), threading.Event()

        def delayed_lookup(rows):
            started.set()
            release.wait(1)
            return [self.owner()]

        resolver = OwnerResolver(delayed_lookup, background=True)
        began = time.monotonic()
        first = resolver.enrich([self.row()])[0]
        self.assertLess(time.monotonic() - began, .2)
        self.assertTrue(started.wait(.2))
        self.assertEqual(first["ownerStatus"], "loading")
        self.assertIsNone(first["loginId"])

        release.set()
        deadline = time.monotonic() + 1
        result = resolver.enrich([self.row()])[0]
        while result["ownerStatus"] == "loading" and time.monotonic() < deadline:
            time.sleep(.01)
            result = resolver.enrich([self.row()])[0]
        self.assertEqual(result["ownerStatus"], "verified")
        self.assertEqual(result["loginId"], "singer-test@example.test")

    def test_conflicting_owner_session_and_profile_hidden(self):
        for owners in [
            [self.owner(), dict(self.owner(), userId="12")],
            [dict(self.owner(), sessionId="82")],
            [dict(self.owner(), profileId="8")],
            [self.owner(), dict(self.owner(), loginId="different@example.test")],
        ]:
            with self.subTest(owners=owners):
                result = OwnerResolver(lambda rows: owners).enrich([self.row(profileId=10)])[0]
                self.assertIsNone(result["userId"])
                self.assertIsNone(result["loginId"])
                self.assertEqual(result["ownerStatus"], "conflict")
        owners = [self.owner(), dict(self.owner(), source="backend-component-job")]
        self.assertEqual(OwnerResolver(lambda rows: owners).enrich([self.row()])[0]["userId"], "5")

    def test_sql_is_read_only_and_rejects_untrusted_ids(self):
        query = ownership_query([self.row(), dict(id="rec_test", kind="recommendation")])
        self.assertTrue(query.startswith("START TRANSACTION READ ONLY;"))
        self.assertTrue(query.endswith("COMMIT;"))
        self.assertIn("WHERE j.ai_job_id IN ('job_owner_test')", query)
        self.assertIn("WHERE r.job_id IN ('rec_test')", query)
        self.assertIn("'loginId',u.email", query)
        self.assertIn("LEFT JOIN users u ON u.user_id=r.user_id", query)
        for word in ["UPDATE", "DELETE", "INSERT", "password", "token", "nickname", "vector"]:
            self.assertNotIn(word, query)
        self.assertIsNone(ownership_query([dict(id="';DROP TABLE users;--", kind="analysis")]))

    def test_approved_snapshot_expiry_and_invalid_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owners.json"
            value = dict(schemaVersion=1, validUntilEpoch=time.time()+60, owners=[self.owner()])
            path.write_text(json.dumps(value), encoding="utf-8")
            result = OwnerResolver(SnapshotLookup(path)).enrich([self.row()])[0]
            self.assertEqual(result["userId"], "5")
            self.assertEqual(result["loginId"], "singer-test@example.test")
            self.assertEqual(result["ownerSource"], ["ownership-snapshot"])
            value["validUntilEpoch"] = time.time()-1
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertEqual(OwnerResolver(SnapshotLookup(path)).enrich([self.row()])[0]["ownerStatus"], "unavailable")
            path.write_text("[]", encoding="utf-8")
            self.assertEqual(OwnerResolver(SnapshotLookup(path)).enrich([self.row()])[0]["ownerStatus"], "unavailable")

    def test_repository_list_and_detail_share_same_owner_without_job_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "job_owner_test/job.json"
            path.parent.mkdir()
            path.write_text(json.dumps(dict(jobId="job_owner_test", status="FAILED", meta=dict(sessionId=101, analysisType="RANGE"))), encoding="utf-8")
            before = path.read_bytes()
            lookup = Mock(return_value=[self.owner()])
            repo = Repository(directory, owners=OwnerResolver(lookup))
            self.assertEqual(repo.list()[0]["userId"], "5")
            self.assertEqual(repo.job("job_owner_test")["userId"], "5")
            self.assertEqual(repo.job("job_owner_test")["loginId"], "singer-test@example.test")
            self.assertEqual(lookup.call_count, 1)
            self.assertEqual(path.read_bytes(), before)
        self.assertEqual(OwnerResolver().enrich([self.row()])[0]["ownerStatus"], "not-configured")

    def test_missing_invalid_and_changed_login_id_are_not_numeric_substitutes(self):
        owner = self.owner()
        owner.pop("loginId")
        lookup = Mock(return_value=[owner])
        clock = Mock(return_value=0)
        resolver = OwnerResolver(lookup, clock=clock)
        result = resolver.enrich([self.row()])[0]
        self.assertEqual(result["userId"], "5")
        self.assertIsNone(result["loginId"])
        self.assertEqual(result["loginIdStatus"], "missing")
        lookup.return_value = [self.owner()]
        clock.return_value = 31
        self.assertEqual(resolver.enrich([self.row()])[0]["loginId"], "singer-test@example.test")
        self.assertEqual(login_identifier(" singer-test@example.test "), "singer-test@example.test")
        for invalid in [None, 5, "", "a"*256, "test\n@example.test", "test\x00@example.test"]:
            self.assertIsNone(login_identifier(invalid))

    def test_signup_login_id_requires_team_authentication(self):
        repo = Mock(spec=Repository)
        repo.job.return_value = OwnerResolver(lambda rows: [self.owner()]).enrich([self.row()])[0]
        auth = ("team-test", "test-password-for-lab-only")
        client = TestClient(create_app({"LAB_USERNAME":auth[0], "LAB_PASSWORD":auth[1]}, repository=repo))
        response = client.get("/api/jobs/job_owner_test")
        self.assertEqual(response.status_code, 401)
        self.assertNotIn("singer-test@example.test", response.text)
        repo.job.assert_not_called()
        response = client.get("/api/jobs/job_owner_test", auth=auth)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["loginId"], "singer-test@example.test")


if __name__ == "__main__":
    unittest.main()
