import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from app import create_app
from demo import generate, write_json
from engine import Engine, DEFAULTS, MODEL_KEYS
from adapters import matrices
from repository import Repository
from timbre_recommender.shelves import score_all_tracks, build_shelves


class LabTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = generate(Path(cls.temp.name) / "synthetic")
        cls.engine = Engine(cls.root / "catalog")
        cls.repo = Repository(cls.root / "analysis", cases=cls.root / "cases")
        cls.auth = ("test-team", "test-only-password-123")
        cls.client = TestClient(create_app({"LAB_USERNAME": cls.auth[0], "LAB_PASSWORD": cls.auth[1]}, cls.engine, cls.repo))

    @classmethod
    def tearDownClass(cls):
        for array in cls.engine.catalog.arrays.values():
            if hasattr(array, '_mmap'): array._mmap.close()
        cls.temp.cleanup()

    def test_auth_required_for_page_assets_and_data(self):
        for path in ["/", "/static/app.js", "/api/status", "/api/map", "/api/requests", "/api/jobs/job_demo_failed"]:
            with self.subTest(path=path):
                self.assertEqual(self.client.get(path).status_code, 401)
                self.assertEqual(self.client.get(path, auth=self.auth).status_code, 200)
        self.assertEqual(self.client.get("/health").status_code, 200)
        self.assertEqual(self.client.get("/api/map", auth=(self.auth[0], "wrong")).status_code, 401)

    def test_baseline_and_default_candidate_match_production(self):
        case = self.repo.case("case_demo")
        result = self.engine.run(case, DEFAULTS)
        rows = score_all_tracks(self.engine.catalog, self.engine.ranges, matrices(case["query"]),
                                query_profile=case["pitchProfile"], genres=case["genres"])
        production = build_shelves(rows, top_k=10, genres=case["genres"], range_enabled=True, seed=42)[0]["items"]
        for side in ["baseline", "candidate"]:
            self.assertEqual([p["track_id"] for p in production], [p["track_id"] for p in result[side]["recommendations"]])
            self.assertEqual([p["final_score"] for p in production], [p["final_score"] for p in result[side]["recommendations"]])

    def test_gate_diagnostics_and_selected_pool_agree(self):
        result = self.engine.run(self.repo.case("case_demo"), DEFAULTS)
        rows = result["baseline"]["rankings"]["ecapa"]
        reasons = {r["id"]: {e["code"] for e in r["exclusions"]} for r in rows}
        self.assertIn("LANGUAGE", reasons["demo-03"])
        self.assertIn("RAP", reasons["demo-04"])
        self.assertIn("PROFILE_UNRELIABLE", reasons["demo-05"])
        self.assertIn("PROFILE_CONFIDENCE", reasons["demo-06"])
        self.assertTrue(any("KEY_SHIFT" in v for v in reasons.values()))
        eligible = {r["id"] for r in rows if r["eligible"]}
        selected = {r["track_id"] for r in result["baseline"]["recommendations"]}
        self.assertTrue(selected.issubset(eligible))

    def test_failed_analysis_and_successful_callback_distinct(self):
        j = self.client.get("/api/jobs/job_demo_failed", auth=self.auth).json()
        self.assertEqual(j["status"], "FAILED")
        self.assertEqual(j["components"][0]["callbackHttp"], 200)
        self.assertTrue(j["components"][0]["accepted"])
        self.assertEqual(j["reasonSource"], "recorded")
        self.assertEqual(len(j["metadataChecks"]), 1)
        self.assertFalse(j["replayAvailable"])

    def test_expired_reason_not_fabricated(self):
        path = self.root / "analysis/job_demo_failed/callback.RANGE.json"
        original = path.read_bytes()
        try:
            path.unlink()
            j = self.repo.job("job_demo_failed")
            self.assertEqual(j["reasonSource"], "unavailable")
            self.assertIsNone(j["failureReason"])
            self.assertIsNotNone(j["reasonUnavailable"])
            self.assertTrue(j["metadataChecks"])
        finally:
            path.write_bytes(original)

    def test_weights_validation_and_no_unknown_settings(self):
        for settings in [{"ecapaWeight": 2.0}, {"maxKeyShift": -1}, {"topK": 0}, {"unknown": 1}, {"useRangeGate": "false"}]:
            r = self.client.post("/api/experiments", auth=self.auth, json={"requestId": "case_demo", "settings": settings})
            self.assertEqual(r.status_code, 422)

    def test_no_vectors_exposed_by_read_apis_or_report(self):
        for route in ["/api/requests", "/api/jobs/job_demo_failed", "/api/map"]:
            text = self.client.get(route, auth=self.auth).text
            self.assertNotIn('"embedding"', text)
            self.assertNotIn('"embeddings"', text)
            self.assertNotIn('"query"', text)
        result = self.client.post("/api/experiments", auth=self.auth, json={"requestId": "case_demo"})
        self.assertEqual(result.status_code, 200)
        self.assertNotIn('"histogram"', result.text)
        self.assertNotIn('"query"', result.text)

    def test_projection_stable_and_batch_mismatch_rejected(self):
        case = self.repo.case("case_demo")
        self.assertEqual(self.engine.project(case["query"]), self.engine.project(case["query"]))
        case["catalogFingerprint"] = "not-the-active-catalog"
        with self.assertRaises(ValueError): self.engine.run(case, DEFAULTS)

    def test_range_fallback_and_gate_off(self):
        case = self.repo.case("case_demo"); case["pitchProfile"] = None
        result = self.engine.run(case, DEFAULTS)
        self.assertFalse(result["rangeAvailable"])
        self.assertFalse(result["baseline"]["rangeGateApplied"])
        self.assertTrue(any(r["eligible"] for r in result["baseline"]["rankings"]["ecapa"]))

    def test_path_traversal_and_unknown_job(self):
        with self.assertRaises(ValueError): self.repo.job("../../runtime")
        with self.assertRaises(ValueError): self.repo.case("case_../../runtime")
        self.assertEqual(self.client.get("/api/jobs/not-found", auth=self.auth).status_code, 404)
        self.assertEqual(self.client.get("/static/users.json", auth=self.auth).status_code, 404)

    def test_no_credentials_fail_closed_and_dev_restricts_remote(self):
        with self.assertRaises(RuntimeError): create_app({}, self.engine, self.repo)
        client = TestClient(create_app({"LAB_DEV_MODE": "1"}, self.engine, self.repo))
        self.assertEqual(client.get("/api/status").status_code, 403)
        local = TestClient(create_app({"LAB_DEV_MODE": "1"}, self.engine, self.repo), client=("127.0.0.1", 50000), base_url="http://localhost")
        self.assertEqual(local.get("/api/status").status_code, 200)
        self.assertEqual(local.get("/api/status", headers={"X-Forwarded-For": "1.1.1.1"}).status_code, 403)
        self.assertEqual(local.get("/api/status", headers={"Host": "untrusted.example"}).status_code, 403)

    def test_no_source_writes(self):
        sources = list((self.root / "analysis").rglob("*")) + list((self.root / "catalog").rglob("*"))
        before = {p: p.read_bytes() for p in sources if p.is_file()}
        self.engine.run(self.repo.case("case_demo"), dict(DEFAULTS, maxKeyShift=6))
        self.repo.list(); self.repo.job("job_demo_failed")
        self.assertEqual(before, {p: p.read_bytes() for p in before})


if __name__ == "__main__": unittest.main()
