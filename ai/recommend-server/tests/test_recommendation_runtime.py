from __future__ import annotations

import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock
import uuid

from fastapi import BackgroundTasks, HTTPException

import catalog_link
import recommendation_api as recommendations


def embeddings():
    return {
        "ECAPA": {"embedding": [1.0] + [0.0] * 191},
        "SINGER_BYOL": {"embedding": [1.0] + [0.0] * 999},
    }


class RecommendationRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.old_jobs_dir = recommendations.JOBS_DIR
        recommendations.JOBS_DIR = Path(self.tmp.name)
        self.addCleanup(setattr, recommendations, "JOBS_DIR", self.old_jobs_dir)
        self.old_jobs = dict(recommendations.JOBS)
        recommendations.JOBS.clear()
        self.addCleanup(recommendations.JOBS.update, self.old_jobs)
        with recommendations._JSON_ADMISSION_LOCK:
            self.old_outstanding = recommendations._JSON_OUTSTANDING
            recommendations._JSON_OUTSTANDING = 0
        self.addCleanup(self._restore_outstanding)

    def _restore_outstanding(self):
        with recommendations._JSON_ADMISSION_LOCK:
            recommendations._JSON_OUTSTANDING = self.old_outstanding

    def test_json_job_persists_stage_timings_and_lifecycle_timestamps(self):
        identifier = str(uuid.uuid4())
        accepted_at = recommendations._utc_now()
        recommendations.JOBS[identifier] = {
            "id": identifier, "status": "queued", "acceptedAt": accepted_at,
            "startedAt": None, "completedAt": None,
        }
        recommendations._save_job(identifier)
        self.assertTrue(recommendations._reserve_json_recommendation())
        result = {"algorithm": {"range_enabled": False}, "recommendations": []}
        catalog_times = {
            "catalogCacheHit": False, "catalogCacheLockWaitMs": 0.1,
            "catalogFingerprintMs": 0.2, "catalogIndexLoadMs": 11.0,
            "catalogIndexVerifyMs": 4.0, "rangeCatalogLoadMs": 5.0,
            "rangeCatalogVerifyMs": 2.0,
        }
        with (mock.patch.object(recommendations, "_configuration_status", return_value=({"index_dir": "index"}, [])),
              mock.patch.object(recommendations.catalog_cache, "load_catalogs", return_value=(object(), object(), catalog_times)),
              mock.patch.object(recommendations, "_excluded_tracks", return_value=()),
              mock.patch.object(recommendations, "build_result", return_value=result),
              mock.patch.object(catalog_link, "apply", side_effect=lambda value: value)):
            recommendations._json_process(identifier, {
                "userEmbedding": embeddings(), "mode": "timbre", "topK": 10,
                "profileId": 1,
            }, time.perf_counter() - 0.01)

        job = recommendations.JOBS[identifier]
        self.assertEqual(job["status"], "complete")
        self.assertIsNotNone(job["acceptedAt"])
        self.assertIsNotNone(job["startedAt"])
        self.assertIsNotNone(job["completedAt"])
        timing = job["timing"]
        for key in (
            "queueWaitMs", "catalogLoadMs", "catalogValidationMs", "catalogLoadAndValidationMs",
            "scoringMs", "resultMappingMs", "resultSaveMs", "totalMs", "catalogCacheHit",
        ):
            self.assertIn(key, timing)
        persisted = json.loads((Path(self.tmp.name) / identifier / "job.json").read_text(encoding="utf-8"))
        self.assertEqual(persisted["timing"], timing)
        self.assertEqual(recommendations._JSON_OUTSTANDING, 0)

    def test_json_admission_bounds_running_plus_waiting_jobs(self):
        with (mock.patch.object(recommendations, "JSON_RECOMMEND_CONCURRENCY", 1),
              mock.patch.object(recommendations, "JSON_RECOMMEND_QUEUE_LIMIT", 2)):
            self.assertTrue(recommendations._reserve_json_recommendation())
            self.assertTrue(recommendations._reserve_json_recommendation())
            self.assertTrue(recommendations._reserve_json_recommendation())
            self.assertFalse(recommendations._reserve_json_recommendation())
            for _ in range(3):
                recommendations._release_json_recommendation()
        self.assertEqual(recommendations._JSON_OUTSTANDING, 0)

    def test_queue_full_rejects_without_creating_a_job(self):
        with (mock.patch.object(recommendations, "_configuration_status", return_value=({}, [])),
              mock.patch.object(catalog_link, "ensure", return_value={"mappedTracks": 1}),
              mock.patch.object(recommendations, "_reserve_json_recommendation", return_value=False)):
            with self.assertRaises(HTTPException) as caught:
                recommendations._create_from_json({
                    "userEmbedding": embeddings(), "mode": "timbre", "topK": 10, "profileId": 1,
                }, BackgroundTasks())
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(recommendations.JOBS, {})


if __name__ == "__main__":
    unittest.main()
