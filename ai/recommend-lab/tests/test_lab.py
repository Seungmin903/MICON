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
from genres import genre_key
from adapters import matrices
from repository import Repository, created_timestamp
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

    def test_final_score_composition_uses_the_same_eligible_pool_without_axis_quota(self):
        result = self.engine.run(self.repo.case("case_demo"), dict(DEFAULTS, compositionMode="finalScore"))
        recommendations = result["candidate"]["recommendations"]
        self.assertTrue(recommendations)
        self.assertTrue(all(item["axis"] == "FINAL_SCORE" for item in recommendations))
        self.assertEqual(
            [item["final_score"] for item in recommendations],
            sorted((item["final_score"] for item in recommendations), reverse=True),
        )

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

    def test_request_order_normalizes_iso_and_unix_created_at(self):
        analysis = self.root / "mixed-time-analysis"
        recommendations = self.root / "mixed-time-recommendations"
        write_json(analysis / "job_iso/job.json", {
            "jobId": "job_iso", "status": "COMPLETED", "createdAt": "2026-09-18T17:32:13",
            "meta": {"analysisType": "RANGE"}, "components": {},
        })
        write_json(recommendations / "job_unix/job.json", {
            "id": "job_unix", "status": "complete", "createdAt": 1789792327.3118126,
        })

        rows = Repository(analysis, recommendations).list()

        self.assertEqual([row["id"] for row in rows], ["job_unix", "job_iso"])
        self.assertIsInstance(rows[0]["createdAt"], str)
        self.assertIn("T", rows[0]["createdAt"])
        self.assertAlmostEqual(created_timestamp(rows[0]["createdAt"]), 1789792327.3118126, delta=1)

    def test_mr_suspected_label_is_surfaced_only_from_recorded_decision(self):
        analysis = self.root / "mr-analysis"
        write_json(analysis / "job_mr/job.json", {
            "jobId": "job_mr", "status": "COMPLETED", "createdAt": "2026-09-21T10:42:41",
            "meta": {"analysisType": "TIMBRE", "sessionId": 146}, "components": {},
            "mrDetection": {"detectorVersion": "mr-energy-v1", "status": "SUSPECTED",
                            "displayLabel": "MR 의심", "requiresSeparation": True,
                            "reasonCodes": ["QUIET_WINDOWS_LOUD", "LOW_DYNAMIC_RANGE"],
                            "metrics": {"lowEnergyFloorRatio": 0.383, "dynamicRangeP95P10": 5.9},
                            "embeddingInput": "separated-vocals",
                            "separation": {"status": "COMPLETED"}},
        })
        write_json(analysis / "job_clean/job.json", {
            "jobId": "job_clean", "status": "COMPLETED", "createdAt": "2026-09-21T10:40:00",
            "meta": {"analysisType": "TIMBRE", "sessionId": 147}, "components": {},
            "mrDetection": {"detectorVersion": "mr-energy-v1", "status": "NOT_SUSPECTED",
                            "displayLabel": None, "requiresSeparation": False, "reasonCodes": [],
                            "metrics": {"lowEnergyFloorRatio": 0.08, "dynamicRangeP95P10": 24.1},
                            "embeddingInput": "raw-recording"},
        })
        write_json(analysis / "job_old/job.json", {
            "jobId": "job_old", "status": "COMPLETED", "createdAt": "2026-09-21T10:38:00",
            "meta": {"analysisType": "TIMBRE"}, "components": {},
        })
        repo = Repository(analysis)

        flags = {row["id"]: row["mrDetection"] for row in repo.list()}

        self.assertIsNone(flags["job_clean"])
        self.assertIsNone(flags["job_old"])
        self.assertEqual(flags["job_mr"]["label"], "MR 의심")
        self.assertEqual(flags["job_mr"]["embeddingInput"], "separated-vocals")
        self.assertEqual(flags["job_mr"]["lowEnergyFloorRatio"], 0.383)
        self.assertEqual(flags["job_mr"]["reasons"], [
            "노래하지 않는 구간의 음량이 노래 구간만큼 큽니다",
            "녹음 전체의 음량 변화 폭이 좁습니다",
        ])
        self.assertEqual(repo.job("job_mr")["mrDetection"], flags["job_mr"])

    def test_expired_timbre_uses_linked_artist_search_centroids_for_map(self):
        analysis = self.root / "expired-analysis"
        artist_jobs = self.root / "artist-jobs"
        write_json(analysis / "job_expired/job.json", {
            "jobId": "job_expired", "status": "COMPLETED", "audioDeleted": True,
            "meta": {"analysisType": "TIMBRE", "analysisId": 31, "sessionId": 119},
            "components": {
                "ECAPA": {"status": "COMPLETED", "accepted": True},
                "SINGER_BYOL": {"status": "COMPLETED", "accepted": True},
            },
        })
        write_json(artist_jobs / "artist-search-job-1/job.json", {
            "jobId": "artist-search-job-1",
            "payload": {"analysisId": 31, "sessionId": 119, "modelResults": {
                "ECAPA": {"embedding": [1.0] + [0.0] * 191},
                "SINGER_BYOL": {"embedding": [1.0] + [0.0] * 999},
            }},
        })
        repo = Repository(analysis, artist_jobs=artist_jobs)

        summary = repo.job("job_expired")
        case = repo.case("job_expired")

        self.assertTrue(summary["replayAvailable"])
        self.assertEqual(summary["replaySource"], "artist-search-centroids")
        self.assertEqual(case["query"]["ecapa"][0], 1.0)
        self.assertIn("대표 벡터", case["warning"])

    def test_genre_labels_join_korean_db_and_english_catalog_tags(self):
        self.assertEqual(genre_key("랩/힙합"), genre_key("Hip-Hop"))
        self.assertEqual(genre_key("댄스/팝"), genre_key("Dance"))
        self.assertEqual(genre_key("포크/어쿠스틱"), genre_key("Folk"))
        self.assertIsNone(genre_key("존재하지 않는 장르"))
        labels = self.engine.info()["genres"]["labels"]
        self.assertEqual({row["label"]: row["key"] for row in labels},
                         {"Ballad": "ballad", "Dance": "dance", "Soundtrack": "ost", "Hip-Hop": "hiphop"})
        self.assertEqual(self.engine.info()["genres"]["untaggedTrackCount"], 0)

    def test_genre_choice_without_weight_keeps_the_current_top_k(self):
        case = self.repo.case("case_demo")
        current = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5))
        chosen = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5, preferredGenres=["댄스/팝"]))
        self.assertEqual([p["track_id"] for p in current["candidate"]["recommendations"]],
                         [p["track_id"] for p in chosen["candidate"]["recommendations"]])
        self.assertEqual(chosen["candidate"]["genre"]["bonusPerMatch"], 0)

    def test_genre_weight_pulls_preferred_genre_into_top_k(self):
        case = self.repo.case("case_demo")
        settings = dict(DEFAULTS, useRangeGate=False, topK=5, preferredGenres=["댄스/팝"])
        for mode in ["twoAxisQuota", "finalScore"]:
            with self.subTest(mode=mode):
                off = self.engine.run(case, dict(settings, compositionMode=mode, genreWeight=0))["candidate"]
                on = self.engine.run(case, dict(settings, compositionMode=mode, genreWeight=1))["candidate"]
                self.assertEqual(off["genre"]["matchedInTopK"], 1)
                self.assertEqual(on["genre"]["matchedInTopK"], 3)
                # 선택 장르가 위로 올라오고, 같은 장르 안에서는 음색 순서가 남는다.
                self.assertTrue(all(p["genreMatch"] for p in on["recommendations"][:3]))
                self.assertEqual(on["genre"]["bonusPerMatch"], on["genre"]["scoreSpan"])
                self.assertEqual(off["eligibleCount"], on["eligibleCount"])

    def test_baseline_ignores_the_experiment_genre_choice(self):
        result = self.engine.run(self.repo.case("case_demo"),
                                 dict(DEFAULTS, useRangeGate=False, topK=5, preferredGenres=["댄스/팝"], genreWeight=1))
        self.assertEqual(result["baseline"]["genre"]["preferred"], ["발라드"])
        self.assertEqual(result["baseline"]["genre"]["source"], "case")
        self.assertEqual(result["baseline"]["genre"]["weight"], 0)
        self.assertEqual(result["candidate"]["genre"]["source"], "experiment")

    def test_unknown_genre_label_is_reported_not_silently_dropped(self):
        result = self.engine.run(self.repo.case("case_demo"),
                                 dict(DEFAULTS, useRangeGate=False, preferredGenres=["없는장르"], genreWeight=1))
        self.assertEqual(result["candidate"]["genre"]["unknownLabels"], ["없는장르"])
        self.assertEqual(result["candidate"]["genre"]["matchedCatalogCount"], 0)

    def test_genre_settings_validation(self):
        for settings in [{"genreWeight": 1.5}, {"genreWeight": -0.1}, {"preferredGenres": "발라드"},
                         {"preferredGenres": ["발라드", "발라드"]}, {"preferredGenres": [""]},
                         {"preferredGenres": ["가" * 41]}, {"preferredGenres": ["장르"] * 13}]:
            with self.subTest(settings=settings):
                response = self.client.post("/api/experiments", auth=self.auth,
                                            json={"requestId": "case_demo", "settings": settings})
                self.assertEqual(response.status_code, 422)


if __name__ == "__main__": unittest.main()
