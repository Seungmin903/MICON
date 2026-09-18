import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from session_insights import SessionInsightsResolver, catalog_artist_ids, session_query
from ownership import OwnerResolver
from repository import Repository
from app import create_app
from fastapi.testclient import TestClient

JID = "artist-search-job-" + "a"*32


class SessionInsightsTest(unittest.TestCase):
    def row(self):
        return dict(id="voice_test", kind="analysis", sessionId="101", userId="5", ownerStatus="verified")

    def records(self):
        scope = dict(sessionId="101", userId="5")
        return [dict(scope, kind="session", sessionStatus="SAVED", rangeStatus="AVAILABLE",
            artistSearchStatus="RUNNING", resultVersion=2, measurementId="20",
            measurementStatus="AVAILABLE", qualityStatus="VALID", fullLowMidi="54.00",
            fullHighMidi="71.00", measurementSource="ON_DEVICE", ruleVersion="guided-range-v7",
            measuredAt="2026-09-18 15:13:00", submittedAt="2026-09-18 15:13:01",
            note_results=["private note detail"], vector=["private vector"]),
            dict(scope, kind="artistJob", componentJobId="175", aiJobId=JID, status="QUEUED",
                attemptNo=1, resultVersion=2, catalogVersion="batch-test", scoringVersion="fusion-v2",
                failureCode=None, failureReason=None, payload="private payload"),
            dict(kind="artistLabel", artistId="898", artistName="테스트 가수")]

    def artist_job(self, aid=898):
        return dict(jobId=JID, status="COMPLETED", calculationStatus="COMPLETED", deliveryStatus="REJECTED",
            callback=dict(http=409, accepted=False, attempts=1), deliveryFailureCode="CALLBACK_REJECTED",
            payload=dict(sessionId=101, resultVersion=2, catalogVersion="batch-test", scoringVersion="fusion-v2",
                         callbackUrl="https://private-callback.test", modelResults=["private vectors"]),
            callbackBody=dict(private="not returned"), artists=[dict(rank=1, artistId=aid,
                artistName="예전 표시 이름", similarity=.6266, ecapaScore=.5062, singerByolScore=.747)])

    def write_job(self, root, job):
        path = Path(root)/JID/"job.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8")
        return path

    def test_actual_registered_range_and_computation_not_mistaken_for_stored_result(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_job(directory, self.artist_job())
            before = path.read_bytes()
            result = SessionInsightsResolver(lambda q:self.records(), directory).enrich(self.row())["voiceInsights"]
            self.assertEqual(result["registeredRange"]["fullLowMidi"], 54)
            self.assertEqual(result["registeredRange"]["fullHighMidi"], 71)
            self.assertFalse(result["registeredRange"]["medianMeasured"])
            self.assertFalse(result["registeredRange"]["stableRangeMeasured"])
            self.assertEqual(result["backendArtists"], [])
            ai = result["aiArtistCalculation"]
            self.assertEqual(ai["calculationStatus"], "COMPLETED")
            self.assertEqual(ai["deliveryStatus"], "REJECTED")
            self.assertFalse(ai["callbackAccepted"])
            self.assertEqual(ai["callbackHttp"], 409)
            self.assertEqual(ai["artists"][0]["artistId"], "898")
            self.assertEqual(ai["artists"][0]["artistName"], "테스트 가수")
            self.assertEqual(ai["artists"][0]["nameSource"], "backend-singer")
            self.assertEqual(path.read_bytes(), before)
            text = json.dumps(result)
            for private in ["private", "payload", "callbackBody", "note_results", "_labels", "_session", "modelResults"]:
                self.assertNotIn(private, text)

    def test_stored_artist_is_authoritative_and_earlier_attempt_identified(self):
        records = self.records()
        records.append(dict(kind="artistResult", sessionId="101", userId="5", componentJobId="160",
            artistId="898", artistName="실제 DB 가수", rank=1, similarity="0.800001",
            ecapaScore="0.7", singerByolScore="0.9", createdAt="2026-09-18 15:00:00", vector="private"))
        result = SessionInsightsResolver(lambda q:records).enrich(self.row())["voiceInsights"]
        self.assertEqual(result["backendArtists"][0]["artistName"], "실제 DB 가수")
        self.assertEqual(result["backendArtists"][0]["similarity"], .800001)
        self.assertTrue(result["backendArtists"][0]["earlierAttempt"])
        self.assertNotIn("vector", json.dumps(result))

    def test_unknown_conflicting_or_missing_session_never_looks_up_another_member(self):
        lookup = Mock(return_value=self.records())
        resolver = SessionInsightsResolver(lookup)
        for row in [dict(self.row(), ownerStatus="conflict"), dict(self.row(), ownerStatus="not-found"),
                    dict(self.row(), sessionId=None), dict(self.row(), userId=None)]:
            self.assertEqual(resolver.enrich(row)["voiceInsights"]["lookupStatus"], "unverified-owner")
        lookup.assert_not_called()
        for kind in ["session", "artistJob", "artistResult"]:
            records = self.records()
            if kind == "artistResult":
                records.append(dict(kind=kind, sessionId="101", userId="12"))
            else:
                next(r for r in records if r["kind"]==kind)["userId"] = "12"
            result = SessionInsightsResolver(lambda q:records).enrich(self.row())["voiceInsights"]
            self.assertEqual(result["lookupStatus"], "conflict")
            self.assertNotIn("registeredRange", result)

    def test_empty_unconfigured_and_expired_failed_lookup_are_not_false_completed_results(self):
        self.assertEqual(SessionInsightsResolver().enrich(self.row())["voiceInsights"]["lookupStatus"], "not-configured")
        self.assertEqual(SessionInsightsResolver(lambda q:[]).enrich(self.row())["voiceInsights"]["lookupStatus"], "not-found")
        clock, lookup = Mock(return_value=0), Mock(return_value=self.records())
        resolver = SessionInsightsResolver(lookup, clock=clock)
        self.assertEqual(resolver.enrich(self.row())["voiceInsights"]["lookupStatus"], "verified")
        resolver.enrich(self.row()); self.assertEqual(lookup.call_count, 1)
        clock.return_value = 11
        lookup.side_effect = OSError("private database secret")
        result = resolver.enrich(self.row())["voiceInsights"]
        self.assertEqual(result["lookupStatus"], "unavailable")
        self.assertNotIn("backendArtists", result)
        self.assertNotIn("private", json.dumps(result))

    def test_missing_or_invalid_range_has_no_filled_in_center_or_bounds(self):
        for low, high in [(None, None), (71,54), ("nan",71), (-1,71), (True,71)]:
            records = self.records()
            records[0].update(fullLowMidi=low, fullHighMidi=high)
            r = SessionInsightsResolver(lambda q:records).enrich(self.row())["voiceInsights"]["registeredRange"]
            self.assertFalse(r["boundsValid"])
            self.assertIsNone(r["fullLowMidi"])
            self.assertIsNone(r["fullHighMidi"])

    def test_historical_null_ids_are_not_guessed_from_artist_name(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write_job(directory, self.artist_job(None))
            ai = SessionInsightsResolver(lambda q:self.records(), directory).enrich(self.row())["voiceInsights"]["aiArtistCalculation"]
            self.assertIsNone(ai["artists"][0]["artistId"])
            self.assertEqual(ai["artists"][0]["artistName"], "예전 표시 이름")
            self.assertEqual(ai["artists"][0]["nameSource"], "historical-ai-record")

    def test_persisted_ai_session_and_versions_must_match_exact_backend_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            for field, value in [("sessionId",82), ("resultVersion",1), ("catalogVersion","other-batch"), ("scoringVersion","other-scoring")]:
                job = self.artist_job(); job["payload"][field] = value
                self.write_job(directory, job)
                ai = SessionInsightsResolver(lambda q:self.records(), directory).enrich(self.row())["voiceInsights"]["aiArtistCalculation"]
                self.assertEqual(ai["lookupStatus"], "conflict")
                self.assertNotIn("artists", ai)
            records = self.records(); records[0]["resultVersion"] = 3
            self.write_job(directory, self.artist_job())
            ai = SessionInsightsResolver(lambda q:records, directory).enrich(self.row())["voiceInsights"]["aiArtistCalculation"]
            self.assertFalse(ai["versionCurrent"])

    def test_artist_job_missing_and_path_escape_not_read(self):
        with tempfile.TemporaryDirectory() as directory:
            for jid in [JID, "../private", "other-job"]:
                records = self.records(); records[1]["aiJobId"] = jid
                ai = SessionInsightsResolver(lambda q:records, directory).enrich(self.row())["voiceInsights"]["aiArtistCalculation"]
                self.assertEqual(ai["lookupStatus"], "not-found")

    def test_queries_are_fixed_readonly_exact_session_user_and_do_not_select_vectors(self):
        query = session_query("5", "101", ["898", "894", "1);DELETE FROM users;--"])
        self.assertTrue(query.startswith("START TRANSACTION READ ONLY;"))
        self.assertTrue(query.endswith("COMMIT;"))
        self.assertIn("s.session_id=101 AND s.user_id=5", query)
        self.assertIn("a.singer_id IN (894,898)", query)
        for word in ["UPDATE", "DELETE", "INSERT", "password", "email", "vector", "note_results", "object_key"]:
            self.assertNotIn(word, query)
        with self.assertRaises(ValueError):session_query("5 OR 1=1", "101")

    def test_sidecar_labels_require_matching_index_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root/"index.json").write_text("{}", encoding="utf-8")
            value = dict(schemaVersion=1,indexSha256=hashlib.sha256(b"{}").hexdigest(), tracks=[dict(artistId=898)])
            (root/"artist_ids.json").write_text(json.dumps(value),encoding="utf-8")
            self.assertEqual(catalog_artist_ids(root), ["898"])
            (root/"index.json").write_text('{"other":true}',encoding="utf-8")
            self.assertEqual(catalog_artist_ids(root), [])

    def test_owner_resolves_exact_source_session_and_refuses_same_user_multiple_sessions(self):
        base = dict(id="rec-test",kind="recommendation",userId="5",sessionId="101",source="backend-recommendation-job")
        row = dict(id="rec-test",kind="recommendation",sessionId=None)
        self.assertEqual(OwnerResolver(lambda r:[base]).enrich([row])[0]["sessionId"], "101")
        row = dict(id="rec-test",kind="recommendation",sessionId=None)
        self.assertEqual(OwnerResolver(lambda r:[base,dict(base,sessionId="82")]).enrich([row])[0]["ownerStatus"], "conflict")

    def test_range_and_artist_detail_require_team_auth(self):
        repo = Mock(spec=Repository)
        repo.job.return_value = SessionInsightsResolver(lambda q:self.records()).enrich(self.row())
        auth = ("team-test", "test-password-for-lab-only")
        client = TestClient(create_app({"LAB_USERNAME":auth[0],"LAB_PASSWORD":auth[1]},repository=repo))
        self.assertEqual(client.get("/api/jobs/voice_test").status_code,401)
        repo.job.assert_not_called()
        result = client.get("/api/jobs/voice_test",auth=auth)
        self.assertEqual(result.status_code,200)
        self.assertEqual(result.json()["voiceInsights"]["registeredRange"]["fullLowMidi"],54)

    def test_old_recommendation_current_profile_is_explicitly_not_historical_source(self):
        row = dict(self.row(),kind="recommendation",sessionId=None,profileId=8,verifiedProfileId="8")
        lookup = Mock(return_value=self.records())
        result = SessionInsightsResolver(lookup).enrich(row)
        self.assertIsNone(result["sessionId"])
        self.assertEqual(result["voiceInsights"]["sessionContext"],"current-profile")
        self.assertEqual(result["voiceInsights"]["currentProfileId"],"8")
        self.assertIn("추천 당시 세션 아님",result["voiceInsights"]["reason"])
        query=lookup.call_args.args[0]
        self.assertIn("p.profile_id=8 AND p.user_id=5",query)
        unverified=dict(row,verifiedProfileId=None)
        self.assertEqual(SessionInsightsResolver(lookup).enrich(unverified)["voiceInsights"]["lookupStatus"],"unverified-owner")
        self.assertEqual(lookup.call_count,1)


if __name__ == "__main__":unittest.main()
