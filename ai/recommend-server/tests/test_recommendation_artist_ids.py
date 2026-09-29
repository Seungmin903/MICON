"""Restored-job metadata repair is local, atomic and outside the polling path."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient

import app
import recommendation_api as recommendations
if __package__:
    from .test_artist_catalog import catalog_fixture
else:
    from test_artist_catalog import catalog_fixture


class RecommendationArtistIdsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.catalog, self.mapping = catalog_fixture(self.root, [("a", "NAME_A", 10), ("b", "NAME_B", 20)])
        for patch in (mock.patch.object(recommendations, "JOBS", {}),
                      mock.patch.object(recommendations, "JOBS_DIR", self.root / "jobs"),
                      mock.patch.object(recommendations, "_load_config", return_value={"index_dir": str(self.root)})):
            patch.start()
            self.addCleanup(patch.stop)

    def job(self, items=None):
        identifier = str(uuid.uuid4())
        recommendations.JOBS[identifier] = {
            "id": identifier, "status": "complete", "progress": 100,
            "payload": {"private": [1, 2]}, "callbackBody": {"private": True},
            "timing": {"scoringMs": 123.5},
            "result": {"recommendations": items if items is not None else [
                {"track_id": "a", "artist": "NAME_A", "final_score": .71, "recommended_key": 0}],
                "payload": {"track_id": "a", "artist_ids": [999]},
                "callbackBody": {"private": True}},
        }
        recommendations._save_job(identifier)
        return identifier

    def persisted(self, identifier):
        return json.loads((recommendations.JOBS_DIR / identifier / "job.json").read_text(encoding="utf-8"))

    def test_startup_repairs_and_persists_once_then_parallel_polling_never_loads_catalogs(self):
        identifier = self.job()
        original = deepcopy(recommendations.JOBS[identifier])
        with mock.patch.object(recommendations.CatalogIndex, "load", return_value=self.catalog) as load:
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 1)
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
            load.assert_called_once_with(str(self.root), verify_hashes=True)
        saved = self.persisted(identifier)
        self.assertEqual(saved, recommendations.JOBS[identifier])
        self.assertEqual(saved["result"]["recommendations"][0]["artist_ids"], [10])
        original["result"]["recommendations"][0]["artist_ids"] = [10]
        self.assertEqual(saved, original)  # Scores, status, times and all private state remain intact.
        # Simulate the next process restoring the repaired JSON file.
        recommendations.JOBS[identifier] = saved
        with (mock.patch.object(recommendations.CatalogIndex, "load", side_effect=AssertionError("polling reloaded index")),
              mock.patch.object(recommendations.ArtistCatalog, "load", side_effect=AssertionError("polling reloaded artists")),
              mock.patch.object(recommendations, "_load_config", side_effect=AssertionError("polling loaded config"))):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
            with ThreadPoolExecutor(max_workers=8) as pool:
                responses = list(pool.map(recommendations.get_recommendation, [identifier] * 32))
        for response in responses:
            self.assertEqual(response["result"]["recommendations"][0]["artist_ids"], [10])
            self.assertNotIn("payload", response)
            self.assertNotIn("callbackBody", response)
            self.assertNotIn("payload", response["result"])

    def test_startup_loads_one_snapshot_for_multiple_jobs_and_exact_song_ids(self):
        first = self.job([{"track_id": "old-track", "song_id": "b"}])
        second = self.job([{"track_id": "a", "artist_ids": [77]},
                           {"track_id": "unknown", "song_id": 10, "artist": "NAME_A"}])
        with (mock.patch.object(recommendations.CatalogIndex, "load", return_value=self.catalog) as load,
              mock.patch.object(recommendations.ArtistCatalog, "load", return_value=self.mapping) as artists):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 2)
            load.assert_called_once()
            artists.assert_called_once_with(self.catalog)
        self.assertEqual(self.persisted(first)["result"]["recommendations"][0]["artist_ids"], [20])
        second_items = self.persisted(second)["result"]["recommendations"]
        self.assertEqual(second_items[0]["artist_ids"], [77])
        self.assertEqual(second_items[1]["artist_ids"], [])

    def test_no_results_or_already_known_ids_do_not_load_an_index(self):
        recommendations.JOBS[str(uuid.uuid4())] = {"status": "queued", "result": None}
        identifier = self.job([{"track_id": "a", "artist_ids": [10]}])
        with mock.patch.object(recommendations.CatalogIndex, "load") as load:
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
            recommendations.get_recommendation(identifier)
            load.assert_not_called()

    def test_http_polling_keeps_shelf_ids_and_never_exposes_internal_input(self):
        identifier = self.job([{"track_id": "a", "artist_ids": [10]}])
        recommendations.JOBS[identifier]["result"]["shelves"] = [
            {"items": [{"track_id": "b", "artist_ids": [20]}]}]
        api = FastAPI()
        api.include_router(recommendations.router)
        with (TestClient(api) as client,
              mock.patch.object(recommendations.CatalogIndex, "load", side_effect=AssertionError("polling reloaded index")),
              mock.patch.object(recommendations, "_save_job", side_effect=AssertionError("polling wrote job"))):
            for _ in range(3):
                response = client.get(f"/recommendations/{identifier}")
                self.assertEqual(response.status_code, 200)
                body = response.json()
                self.assertEqual(body["result"]["recommendations"][0]["artist_ids"], [10])
                self.assertEqual(body["result"]["shelves"][0]["items"][0]["artist_ids"], [20])
                self.assertNotIn("payload", body)
                self.assertNotIn("payload", body["result"])
                self.assertNotIn("callbackBody", body)

    def test_unavailable_or_mismatched_snapshot_keeps_old_jobs_and_query_available(self):
        identifier = self.job()
        original = deepcopy(recommendations.JOBS[identifier])
        with mock.patch.object(recommendations.CatalogIndex, "load", side_effect=ValueError("unsupported v2")):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
        self.assertEqual(recommendations.JOBS[identifier], original)
        self.assertEqual(self.persisted(identifier), original)
        self.assertEqual(recommendations.get_recommendation(identifier)["result"]["recommendations"][0]["artist_ids"], [])
        snapshot = json.loads((self.root / "artist_ids.json").read_text(encoding="utf-8"))
        snapshot["indexSha256"] = "0" * 64
        (self.root / "artist_ids.json").write_text(json.dumps(snapshot), encoding="utf-8")
        with mock.patch.object(recommendations.CatalogIndex, "load", return_value=self.catalog):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
        self.assertEqual(self.persisted(identifier), original)

    def test_failed_atomic_replace_does_not_publish_partial_ids_and_can_retry(self):
        identifier = self.job()
        original = deepcopy(recommendations.JOBS[identifier])
        with (mock.patch.object(recommendations.CatalogIndex, "load", return_value=self.catalog),
              mock.patch.object(recommendations.os, "replace", side_effect=PermissionError("locked")),
              mock.patch.object(recommendations.time, "sleep")):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
        self.assertEqual(recommendations.JOBS[identifier], original)
        self.assertEqual(self.persisted(identifier), original)
        with mock.patch.object(recommendations.CatalogIndex, "load", return_value=self.catalog):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 1)
        self.assertEqual(self.persisted(identifier)["result"]["recommendations"][0]["artist_ids"], [10])

    def test_startup_rechecks_current_job_after_catalog_load_without_overwriting_a_new_result(self):
        identifier = self.job()
        def finish_new_job(*args, **kwargs):
            recommendations._update(identifier, result={"recommendations": [
                {"track_id": "a", "artist_ids": [77], "final_score": .92}]})
            return self.catalog
        with mock.patch.object(recommendations.CatalogIndex, "load", side_effect=finish_new_job):
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
        self.assertEqual(self.persisted(identifier)["result"]["recommendations"][0]["artist_ids"], [77])

    def test_invalid_job_identity_is_not_written_to_a_path(self):
        recommendations.JOBS["../not-a-uuid"] = {"result": {"recommendations": [{"track_id": "a"}]}}
        with mock.patch.object(recommendations.CatalogIndex, "load") as load:
            self.assertEqual(recommendations.repair_recommendation_artist_ids(), 0)
            load.assert_not_called()

    def test_app_lifespan_runs_metadata_repair_once_without_inference_or_network_in_repair(self):
        stop = threading.Event()
        with (mock.patch.object(app.analysis_jobs, "restore_jobs"),
              mock.patch.object(app, "restore_artist_jobs"),
              mock.patch.object(app, "repair_recommendation_artist_ids") as repair,
              mock.patch.object(app.catalog_link, "refresh"),
              mock.patch.object(app.analysis_jobs, "preload_timbre_runtime"),
              mock.patch.object(app.analysis_jobs, "start_sweeper", return_value=stop)):
            async def start_and_stop():
                async with app.lifespan(app.app):
                    repair.assert_called_once_with()
            asyncio.run(start_and_stop())
        self.assertTrue(stop.is_set())
