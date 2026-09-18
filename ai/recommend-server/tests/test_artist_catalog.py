from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from fastapi import BackgroundTasks
import numpy as np

from artist_catalog import ArtistCatalog, make_snapshot, public_response
import analysis_jobs
import recommendation_api
import song_fit_api
from schemas import Recommendation
from timbre_recommender.catalog import CatalogIndex


def catalog_fixture(root: Path, identities: list[tuple[str, str, int]]) -> tuple[CatalogIndex, ArtistCatalog]:
    metadata = {"tracks": [{"track_id": tid, "title": "title-"+tid, "artist": name,
        "metadata": {"song_id": tid, "batch_id": "batch-1"},
        "arrays": {k: {"offset": i, "count": 1} for k in ("ecapa", "singer_byol")}}
        for i, (tid, name, _) in enumerate(identities)]}
    (root / "index.json").write_text(json.dumps(metadata), encoding="utf-8")
    arrays = {}
    for key, dimension in (("ecapa", 192), ("singer_byol", 1000)):
        rows = np.zeros((len(identities), dimension))
        rows[:, 0] = 1
        arrays[key] = rows
    catalog = CatalogIndex(root, metadata, arrays)
    relations = [{"songId": tid, "artistId": aid, "role": "MAIN"} for tid, _, aid in identities]
    body = make_snapshot(catalog, relations, 3)
    (root / "artist_ids.json").write_text(json.dumps(body), encoding="utf-8")
    return catalog, ArtistCatalog.load(catalog)


class ArtistCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.catalog, self.mapping = catalog_fixture(self.root, [("a", "NAME_A", 10), ("b", "NAME_B", 20)])

    def tearDown(self):
        self.temporary.cleanup()

    def test_snapshot_has_exact_ids_and_no_names(self):
        body = json.loads((self.root / "artist_ids.json").read_text())
        self.assertEqual(self.mapping.by_track, {"a": 10, "b": 20})
        self.assertNotIn("NAME_A", json.dumps(body))
        self.assertEqual(self.mapping.source_revision, 3)

    def test_invalid_snapshot_is_rejected_without_name_fallback(self):
        original = json.loads((self.root / "artist_ids.json").read_text())
        mutations = [lambda b: b.update(catalogVersion="wrong"),
            lambda b: b.update(indexSha256="0"*64), lambda b: b.update(sourceRevision=True),
            lambda b: b["tracks"].pop(), lambda b: b["tracks"].append(b["tracks"][0]),
            lambda b: b["tracks"][0].update(artistId=None),
            lambda b: b["tracks"][0].update(artistId=True),
            lambda b: b["tracks"][0].update(artistId=0),
            lambda b: b["tracks"][0].update(artistId="10"),
            lambda b: b["tracks"][0].update(role="FEATURED"),
            lambda b: b["tracks"][0].update(trackId="unknown"),
            lambda b: b["tracks"][0].update(songId="wrong")]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                body = deepcopy(original)
                mutate(body)
                with self.assertRaises(ValueError):
                    ArtistCatalog.validate(body, self.catalog)

    def test_index_changes_invalidate_snapshot(self):
        with (self.root / "index.json").open("a") as stream:
            stream.write(" ")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            ArtistCatalog.load(self.catalog)

    def test_export_rejects_multiple_or_missing_singers(self):
        for relations in ([], [{"songId": "a", "artistId": 10, "role": "MAIN"}]*2):
            with self.assertRaises(ValueError):
                make_snapshot(self.catalog, relations, 3)

    def test_aliases_merge_by_id_not_name(self):
        catalog, mapping = catalog_fixture(self.root, [("a", "ALIAS_A", 10), ("b", "ALIAS_B", 10)])
        result = song_fit_api.rank_artists({k: v[:1] for k,v in catalog.arrays.items()}, catalog, 5, mapping)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["artistId"], 10)
        self.assertNotIn("artistName", result[0])

    def test_same_name_different_ids_remain_separate_with_id_tie_break(self):
        catalog, mapping = catalog_fixture(self.root, [("a", "SAME_NAME", 20), ("b", "SAME_NAME", 10)])
        result = song_fit_api.rank_artists({k: v[:1] for k,v in catalog.arrays.items()}, catalog, 5, mapping)
        self.assertEqual([x["artistId"] for x in result], [10, 20])
        self.assertEqual([x["rank"] for x in result], [1, 2])
        self.assertEqual(set(result[0]), {"rank", "artistId", "similarity", "ecapaScore", "singerByolScore"})

    def test_public_projection_removes_nested_names_and_internal_inputs(self):
        value = {"preferred_artists": ["NAME_A"], "payload": {"artistName": "PRIVATE"},
            "callbackBody": {"artistName": "PRIVATE"}, "shelves": [{"preferredArtists": ["NAME_B"],
            "items": [{"track_id": "a", "artist": "NAME_A", "metadata": {"singer_name": "NAME_A"}}]}],
            "result": {"sourceArtistName": "NAME_A"}}
        before = deepcopy(value)
        projected = public_response(value, self.mapping, self.catalog)
        self.assertEqual(projected["preferred_artist_ids"], [10])
        self.assertEqual(projected["shelves"][0]["items"][0]["artist_ids"], [10])
        self.assertNotIn("NAME_", json.dumps(projected))
        self.assertNotIn("PRIVATE", json.dumps(projected))
        self.assertEqual(value, before)

    def test_historical_recommendation_get_is_projected_not_returned_raw(self):
        jid = "00000000-0000-4000-8000-000000000001"
        with mock.patch.dict(recommendation_api.JOBS, {jid: {"id": jid,
             "preferred_artists": ["NAME_A"], "result": {"recommendations": [{"track_id": "a", "artist": "NAME_A"}]}}}), \
             mock.patch.object(recommendation_api, "_configuration_status", return_value=({"index_dir": str(self.root)}, [])), \
             mock.patch.object(recommendation_api.CatalogIndex, "load", return_value=self.catalog):
            body = recommendation_api.get_recommendation(jid)
        self.assertNotIn("NAME_A", json.dumps(body))
        self.assertEqual(body["result"]["recommendations"][0]["artist_ids"], [10])

    def test_historical_ids_use_exact_db_song_id_without_guessing(self):
        body = public_response([
            {"track_id": "old", "song_id": "a", "artist": "WRONG_NAME"},
            {"track_id": "unknown", "song_id": 10, "artist": "NAME_A"}], self.mapping, self.catalog)
        self.assertEqual(body[0]["artist_ids"], [10])
        self.assertEqual(body[1]["artist_ids"], [])

    def test_voice_job_get_does_not_echo_artist_names(self):
        jid = "job_" + "a"*32
        with mock.patch.dict(analysis_jobs.JOBS, {jid: {"jobId": jid, "meta": {"artistName": "NAME_A"}}}):
            self.assertNotIn("NAME_A", json.dumps(analysis_jobs.get_job(jid)))

    def test_song_fit_does_not_echo_artist_name_fields(self):
        body = song_fit_api.song_fit({"recommendationScores": {"artistName": "NAME_A", "timbre": .8},
            "burdenSections": [{"singer_name": "NAME_A", "sectionId": 1}]})
        self.assertNotIn("NAME_A", json.dumps(body))
        self.assertEqual(body["recommendationScores"]["timbre"], .8)


class ArtistDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.path_patch = mock.patch.object(song_fit_api, "ARTIST_JOBS_DIR", self.root)
        self.jobs_patch = mock.patch.object(song_fit_api, "JOBS", {})
        self.path_patch.start()
        self.jobs_patch.start()
        self.jid = "artist-search-job-" + "a"*32
        song_fit_api.JOBS[self.jid] = {"jobId": self.jid, "requestId": "request-a", "status": "COMPLETED",
            "attemptNo": 1, "resultVersion": 2, "catalogVersion": "batch-1", "payload": {},
            "artists": [{"rank": 1, "artistId": 10, "similarity": .8}],
            "callbackBody": {"status": "COMPLETED", "artists": [{"rank": 1, "artistId": 10, "similarity": .8}]}}

    def tearDown(self):
        self.jobs_patch.stop()
        self.path_patch.stop()
        self.temporary.cleanup()

    def test_callback_rejected_is_not_reported_as_delivery_success_and_can_recalculate(self):
        with mock.patch.object(song_fit_api.analysis_jobs, "post_callback", return_value={"http":409,"accepted":False,"attempts":1}):
            song_fit_api._deliver_artist_job(self.jid)
        job = song_fit_api.JOBS[self.jid]
        self.assertEqual(job["status"], "COMPLETED")
        self.assertEqual(job["deliveryStatus"], "REJECTED")
        self.assertEqual(job["deliveryFailureCode"], "CALLBACK_REJECTED")
        tasks = BackgroundTasks()
        song_fit_api.retry_artist_search(self.jid, tasks)
        self.assertEqual(job["status"], "QUEUED")
        self.assertEqual(len(tasks.tasks), 1)

    def test_network_failure_is_pending_retry_and_body_survives_restore(self):
        with mock.patch.object(song_fit_api.analysis_jobs, "post_callback", return_value={"http":None,"accepted":False,"attempts":3}):
            song_fit_api._deliver_artist_job(self.jid)
        self.assertEqual(song_fit_api.JOBS[self.jid]["deliveryStatus"], "PENDING_RETRY")
        song_fit_api.JOBS.clear()
        with mock.patch.object(song_fit_api.analysis_jobs, "post_callback") as post:
            song_fit_api.restore_artist_jobs()
        post.assert_not_called()
        self.assertEqual(song_fit_api.JOBS[self.jid]["callbackBody"]["artists"][0]["artistId"], 10)

    def test_redelivery_does_not_recompute_or_duplicate_accepted_delivery(self):
        tasks = BackgroundTasks()
        song_fit_api.redeliver_artist_search(self.jid, tasks)
        self.assertEqual(len(tasks.tasks), 1)
        with mock.patch.object(song_fit_api.analysis_jobs, "post_callback", return_value={"http":200,"accepted":True,"attempts":1}) as post:
            song_fit_api._deliver_artist_job(self.jid)
            song_fit_api._deliver_artist_job(self.jid)
        self.assertEqual(post.call_count, 1)
        self.assertEqual(song_fit_api.JOBS[self.jid]["deliveryStatus"], "ACCEPTED")
        self.assertEqual(len(BackgroundTasks().tasks), 0)

    def test_legacy_null_id_result_requires_recalculation_not_redelivery(self):
        song_fit_api.JOBS[self.jid]["callbackBody"]["artists"][0]["artistId"] = None
        with self.assertRaises(song_fit_api.HTTPException) as raised:
            song_fit_api.redeliver_artist_search(self.jid, BackgroundTasks())
        self.assertEqual(raised.exception.status_code, 409)

    def test_artist_get_has_no_request_vectors_or_legacy_name_fields(self):
        song_fit_api.JOBS[self.jid]["artists"][0]["artistName"] = "NAME_A"
        body = song_fit_api.get_artist_search(self.jid)
        self.assertNotIn("payload", body)
        self.assertNotIn("callbackBody", body)
        self.assertNotIn("NAME_A", json.dumps(body))

    def test_insufficient_data_uses_backend_supported_failed_status(self):
        body = song_fit_api._artist_failure(song_fit_api.JOBS[self.jid], "INSUFFICIENT_DATA", "Insufficient input")
        self.assertEqual(body["status"], "FAILED")
        self.assertEqual(body["failureCode"], "INSUFFICIENT_DATA")

    def test_restore_interrupted_computation_is_explicit_failure(self):
        job = song_fit_api.JOBS[self.jid]
        job.update(status="RUNNING", callbackBody=None)
        song_fit_api._save_artist_job(job)
        song_fit_api.JOBS.clear()
        song_fit_api.restore_artist_jobs()
        self.assertEqual(song_fit_api.JOBS[self.jid]["failureCode"], "AI_ENGINE_RESTARTED")

    def test_retry_claim_is_atomic_and_prevents_second_recalculation(self):
        song_fit_api.retry_artist_search(self.jid, BackgroundTasks())
        with self.assertRaises(song_fit_api.HTTPException) as raised:
            song_fit_api.retry_artist_search(self.jid, BackgroundTasks())
        self.assertEqual(raised.exception.status_code, 409)

    def test_restored_legacy_callback_rejection_is_visible(self):
        job = song_fit_api.JOBS[self.jid]
        job.update(callback={"http": 409, "accepted": False, "attempts": 1}, callbackBody=None)
        song_fit_api._save_artist_job(job)
        song_fit_api.JOBS.clear()
        song_fit_api.restore_artist_jobs()
        self.assertEqual(song_fit_api.get_artist_search(self.jid)["deliveryStatus"], "REJECTED")


if __name__ == "__main__":
    unittest.main()
