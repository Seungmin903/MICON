import copy
import json
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from app import create_app
from demo import generate, write_json
from engine import Engine, DEFAULTS, MODEL_KEYS
import genres as genre_rules
from genres import canonical
from adapters import matrices
from repository import Repository, created_timestamp
from timbre_recommender.shelves import score_all_tracks, build_shelves
from timbre_recommender.composition import FINAL_SCORE_COMPOSITION_VERSION, MAX_SONGS_PER_ARTIST


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
        for path in ["/", "/static/app.js", "/api/status", "/api/map", "/api/requests", "/api/requests/case_demo/points", "/api/jobs/job_demo_failed"]:
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
        # 현행(baseline)은 언제나 운영 그대로다.
        self.assertEqual([p["track_id"] for p in production], [p["track_id"] for p in result["baseline"]["recommendations"]])
        self.assertEqual([p["final_score"] for p in production], [p["final_score"] for p in result["baseline"]["recommendations"]])
        # 후보는 기본값에서 언어 게이트를 쓰지 않는다(대안). 그것만 켜면 다시 운영과 같아야 한다.
        same = self.engine.run(case, dict(DEFAULTS, useLanguageGate=True))
        self.assertEqual([p["track_id"] for p in production], [p["track_id"] for p in same["candidate"]["recommendations"]])
        self.assertEqual([p["final_score"] for p in production], [p["final_score"] for p in same["candidate"]["recommendations"]])

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

    def test_current_mode_delegates_to_the_production_final_score_composer(self):
        result = self.engine.run(self.repo.case("case_demo"), DEFAULTS)
        recommendations = result["candidate"]["recommendations"]
        self.assertTrue(recommendations)
        self.assertEqual(DEFAULTS["compositionMode"], "finalScore")
        # 운영 compose_by_final_score 의 응답 모양. 축 할당이 없으므로 axis 는 비어 있다.
        self.assertTrue(all(item["axis"] is None and item["label"] == "최종 점수 순" for item in recommendations))
        self.assertTrue(all(item["composition_version"] == FINAL_SCORE_COMPOSITION_VERSION for item in recommendations))
        self.assertEqual(
            [item["final_score"] for item in recommendations],
            sorted((item["final_score"] for item in recommendations), reverse=True),
        )

    def test_current_mode_applies_the_production_artist_cap(self):
        result = self.engine.run(self.repo.case("case_demo"), dict(DEFAULTS, useRangeGate=False, topK=10))
        for side in ["baseline", "candidate"]:
            counts = Counter(item["artist"] for item in result[side]["recommendations"])
            self.assertTrue(counts)
            self.assertLessEqual(max(counts.values()), MAX_SONGS_PER_ARTIST, side)

    def test_range_gate_uses_the_current_outside_time_ratio_rule(self):
        result = self.engine.run(self.repo.case("case_demo"), DEFAULTS)
        rows = result["candidate"]["rankings"]["ecapa"]
        codes = {code for r in rows for code in (e["code"] for e in r["exclusions"])}
        self.assertIn("OUTSIDE_RANGE", codes)
        # 핵심 음역 초과 반음은 운영 조건이 아니므로 기본값에서는 아무도 떨어뜨리지 않는다.
        self.assertNotIn("CORE_EXCESS", codes)
        strict = self.engine.run(self.repo.case("case_demo"), dict(DEFAULTS, maxCoreExcess=0))
        self.assertIn("CORE_EXCESS", {code for r in strict["candidate"]["rankings"]["ecapa"]
                                      for code in (e["code"] for e in r["exclusions"])})

    def test_failed_analysis_and_successful_callback_distinct(self):
        j = self.client.get("/api/jobs/job_demo_failed", auth=self.auth).json()
        self.assertEqual(j["status"], "FAILED")
        self.assertEqual(j["components"][0]["callbackHttp"], 200)
        self.assertTrue(j["components"][0]["accepted"])
        self.assertEqual(j["reasonSource"], "recorded")
        self.assertEqual(len(j["metadataChecks"]), 1)
        self.assertFalse(j["replayAvailable"])

    def test_request_list_defers_projection_until_map_selection(self):
        original = self.engine.project
        calls = []

        def project(query):
            calls.append(query)
            return original(query)

        self.engine.project = project
        try:
            listed = self.client.get("/api/requests", auth=self.auth)
            self.assertEqual(listed.status_code, 200)
            self.assertEqual(calls, [])
            projected = self.client.get("/api/requests/case_demo/points", auth=self.auth)
            self.assertEqual(projected.status_code, 200)
            self.assertEqual(len(calls), 1)
            self.assertIn("ecapa", projected.json()["points"])
        finally:
            self.engine.project = original

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
        # 기준 표기는 DB 라벨 자신이다. 예전 영어 태그와 표기 변형은 DB 라벨로 접힌다.
        self.assertEqual(canonical("Hip-Hop"), "랩/힙합")
        self.assertEqual(canonical("Dance"), "댄스/팝")
        self.assertEqual(canonical("록/메탈"), "락/메탈")
        self.assertEqual(canonical("발라드"), "발라드")
        self.assertIsNone(canonical("존재하지 않는 장르"))
        labels = self.engine.info()["genres"]["labels"]
        self.assertEqual({row["label"]: row["canonical"] for row in labels},
                         {"발라드": "발라드", "댄스/팝": "댄스/팝", "OST": "OST", "Hip-Hop": "랩/힙합"})
        self.assertEqual(self.engine.info()["genres"]["untaggedTrackCount"], 0)

    def test_genre_choice_without_pushdown_keeps_the_current_top_k(self):
        case = self.repo.case("case_demo")
        current = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5))
        chosen = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5, selectedGenres=["댄스/팝"]))
        self.assertEqual([p["track_id"] for p in current["candidate"]["recommendations"]],
                         [p["track_id"] for p in chosen["candidate"]["recommendations"]])
        self.assertEqual(chosen["candidate"]["genre"]["penaltyPerMiss"], 0)

    def test_pushdown_keeps_unselected_genres_out_of_top_k(self):
        case = self.repo.case("case_demo")
        settings = dict(DEFAULTS, useRangeGate=False, topK=5, selectedGenres=["댄스/팝"])
        for mode in ["finalScore", "twoAxisQuota"]:
            with self.subTest(mode=mode):
                off = self.engine.run(case, dict(settings, compositionMode=mode, genrePushdown=0))["candidate"]
                on = self.engine.run(case, dict(settings, compositionMode=mode, genrePushdown=1))["candidate"]
                self.assertLess(off["genre"]["matchedInTopK"], on["genre"]["matchedInTopK"])
                # 합성 카탈로그의 댄스 곡 3곡이 전부 Top-5 에 들어온다. 남는 자리만 다른 장르가 채운다.
                self.assertEqual(on["genre"]["matchedInTopK"], 3)
                selected_ids = {p["track_id"] for p in on["recommendations"] if p["genreMatch"]}
                self.assertEqual(selected_ids, {"demo-07", "demo-08", "demo-09"})
                # 후보 풀 자체는 그대로다. 감점은 걸러내는 게 아니라 순위를 미는 것이다.
                self.assertEqual(off["eligibleCount"], on["eligibleCount"])

    def test_pushdown_is_scaled_to_the_top_k_competition_band_not_the_whole_spread(self):
        case = self.repo.case("case_demo")
        side = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5,
                                          selectedGenres=["댄스/팝"], genrePushdown=1))["candidate"]
        info = side["genre"]
        self.assertAlmostEqual(info["band"], info["bandTop"] - info["bandCutoff"], places=6)
        self.assertAlmostEqual(info["penaltyPerMiss"], info["band"], places=6)
        scores = [row["finalScore"] for row in side["rankings"]["ecapa"] if row["eligible"]]
        # 전체 점수 폭보다 좁아야 한다. 전체 폭을 쓰면 슬라이더가 곧바로 하드 필터가 된다.
        self.assertLess(info["band"], max(scores) - min(scores))

    def test_pushdown_is_not_a_hard_filter(self):
        """장르가 달라도 음색이 압도적이면 뚫고 들어올 수 있어야 한다."""
        case = self.repo.case("case_demo")
        # demo-00 은 쿼리 본인 구간이라 점수가 압도적으로 높다. 그 장르를 빼고 밀어내도 남아야 한다.
        side = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, topK=5,
                                          selectedGenres=["댄스/팝"], genrePushdown=1))["candidate"]
        self.assertIn("demo-00", [p["track_id"] for p in side["recommendations"]])
        self.assertTrue(side["genre"]["breakthroughs"])
        self.assertTrue(all(not entry["genres"] or "댄스/팝" not in entry["genres"]
                            for entry in side["genre"]["breakthroughs"]))

    def test_language_gate_is_off_by_default_and_can_be_switched_back_on(self):
        case = self.repo.case("case_demo")
        off = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, selectedGenres=["발라드"]))["candidate"]
        on = self.engine.run(case, dict(DEFAULTS, useRangeGate=False, selectedGenres=["발라드"],
                                        useLanguageGate=True))["candidate"]
        self.assertFalse(off["genre"]["languageGate"])
        self.assertEqual(off["genre"]["languageExcludedCount"], 0)
        # 현행 방식을 켜면 영어 곡 demo-03 이 언어 때문에 빠진다.
        self.assertTrue(on["genre"]["languageGate"])
        self.assertEqual(on["genre"]["languageExcludedCount"], 1)
        self.assertGreater(off["eligibleCount"], on["eligibleCount"])
        # 현행(baseline)은 언제나 운영 그대로이므로 언어 게이트가 걸린 상태다.
        self.assertTrue(off["genre"]["languageGate"] is False)

    def test_untagged_songs_are_not_pushed_down(self):
        """장르 메타데이터가 없다는 이유로 곡이 조용히 사라지면 안 된다."""
        self.assertTrue(genre_rules.track_matches({"genres": []}, {"댄스/팝"}))
        self.assertTrue(genre_rules.track_matches({}, {"댄스/팝"}))
        self.assertTrue(genre_rules.track_matches({"genres": ["K-Pop"]}, {"댄스/팝"}))
        self.assertFalse(genre_rules.track_matches({"genres": ["발라드"]}, {"댄스/팝"}))

    def test_baseline_ignores_the_experiment_genre_choice(self):
        result = self.engine.run(self.repo.case("case_demo"),
                                 dict(DEFAULTS, useRangeGate=False, topK=5, selectedGenres=["댄스/팝"], genrePushdown=1))
        self.assertEqual(result["baseline"]["genre"]["selected"], ["발라드"])
        self.assertEqual(result["baseline"]["genre"]["source"], "case")
        self.assertEqual(result["baseline"]["genre"]["pushdown"], 0)
        self.assertTrue(result["baseline"]["genre"]["languageGate"])
        self.assertEqual(result["candidate"]["genre"]["source"], "experiment")

    def test_unknown_genre_label_is_reported_not_silently_dropped(self):
        result = self.engine.run(self.repo.case("case_demo"),
                                 dict(DEFAULTS, useRangeGate=False, selectedGenres=["없는장르"], genrePushdown=1))
        self.assertEqual(result["candidate"]["genre"]["unknownLabels"], ["없는장르"])
        self.assertEqual(result["candidate"]["genre"]["canonical"], [])

    def test_genre_settings_validation(self):
        for settings in [{"genrePushdown": 3.5}, {"genrePushdown": -0.1}, {"selectedGenres": "발라드"},
                         {"selectedGenres": ["발라드", "발라드"]}, {"selectedGenres": [""]},
                         {"selectedGenres": ["가" * 41]}, {"selectedGenres": ["장르"] * 13}]:
            with self.subTest(settings=settings):
                response = self.client.post("/api/experiments", auth=self.auth,
                                            json={"requestId": "case_demo", "settings": settings})
                self.assertEqual(response.status_code, 422)


if __name__ == "__main__": unittest.main()
