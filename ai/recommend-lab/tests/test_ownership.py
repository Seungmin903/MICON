import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ownership import OwnerResolver, SnapshotLookup, numeric_id, ownership_query
from repository import Repository


class OwnershipTest(unittest.TestCase):
    def row(self, **extra):
        return dict(id="job_owner_test", kind="analysis", sessionId=101, **extra)

    def owner(self, **extra):
        return dict(id="job_owner_test", kind="analysis", sessionId="101", userId="5", source="backend-analysis-job", **extra)

    def test_direct_job_lookup_preserves_bigint_and_does_not_infer_session_owner(self):
        lookup = Mock(return_value=[self.owner()])
        rows = [self.row(), dict(id="job_other", kind="analysis", sessionId=101), dict(id="case_synthetic", kind="case")]
        result = OwnerResolver(lookup).enrich(rows)
        self.assertEqual(result[0]["userId"], "5")
        self.assertEqual(result[0]["ownerStatus"], "verified")
        self.assertIsNone(result[1]["userId"])
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
        self.assertEqual(result["ownerStatus"], "unavailable")
        self.assertNotIn("private", json.dumps(result))
        resolver.enrich([self.row()])
        self.assertEqual(lookup.call_count, 3)

    def test_conflicting_owner_session_and_profile_hidden(self):
        for owners in [
            [self.owner(), dict(self.owner(), userId="12")],
            [dict(self.owner(), sessionId="82")],
            [dict(self.owner(), profileId="8")],
        ]:
            with self.subTest(owners=owners):
                result = OwnerResolver(lambda rows: owners).enrich([self.row(profileId=10)])[0]
                self.assertIsNone(result["userId"])
                self.assertEqual(result["ownerStatus"], "conflict")
        owners = [self.owner(), dict(self.owner(), source="backend-component-job")]
        self.assertEqual(OwnerResolver(lambda rows: owners).enrich([self.row()])[0]["userId"], "5")

    def test_sql_is_read_only_and_rejects_untrusted_ids(self):
        query = ownership_query([self.row(), dict(id="rec_test", kind="recommendation")])
        self.assertTrue(query.startswith("START TRANSACTION READ ONLY;"))
        self.assertTrue(query.endswith("COMMIT;"))
        self.assertIn("WHERE j.ai_job_id IN ('job_owner_test')", query)
        self.assertIn("WHERE r.job_id IN ('rec_test')", query)
        for word in ["UPDATE", "DELETE", "INSERT", "email", "nickname", "vector"]:
            self.assertNotIn(word, query)
        self.assertIsNone(ownership_query([dict(id="';DROP TABLE users;--", kind="analysis")]))

    def test_approved_snapshot_expiry_and_invalid_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owners.json"
            value = dict(schemaVersion=1, validUntilEpoch=time.time()+60, owners=[self.owner()])
            path.write_text(json.dumps(value), encoding="utf-8")
            result = OwnerResolver(SnapshotLookup(path)).enrich([self.row()])[0]
            self.assertEqual(result["userId"], "5")
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
            self.assertEqual(lookup.call_count, 1)
            self.assertEqual(path.read_bytes(), before)
        self.assertEqual(OwnerResolver().enrich([self.row()])[0]["ownerStatus"], "not-configured")


if __name__ == "__main__":
    unittest.main()
