from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

from fastapi import FastAPI, BackgroundTasks, HTTPException
from fastapi.testclient import TestClient
import numpy as np
import soundfile as sf

import analysis_jobs as jobs
import recommendation_api as recommendations
import catalog_link
from timbre_recommender.composition import song_id_of
from service_auth import require_service_token
from fastapi import Depends


def wav(rate=48000):
    out = io.BytesIO()
    sf.write(out, np.sin(np.arange(rate // 5) * (2 * np.pi * 220 / rate)) * .2,
             rate, format="WAV", subtype="PCM_16")
    return out.getvalue()


def meta(kind="TIMBRE"):
    return dict(requestId=str(uuid.uuid4()), analysisId=12, sessionId=8, attemptNo=1,
                analysisType=kind, components=["RANGE"] if kind == "RANGE" else ["ECAPA", "SINGER_BYOL"],
                contractVersion="v1", measurementId="measurement-1")


def vector(size):
    return [1.0] + [0.0] * (size - 1)


def embeddings():
    return {name: dict(modelVersion="checkpoint-v1", dimension=size, embedding=vector(size),
                       segmentInfo=dict(segmentCount=1, startTimes=[0.0], embeddings=[vector(size)]))
            for name, size in (("ECAPA", 192), ("SINGER_BYOL", 1000))}


class VoiceContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.patch = mock.patch.object(jobs, "ANALYSIS_DIR", Path(self.tmp.name))
        self.patch.start()
        self.addCleanup(self.patch.stop)
        jobs.JOBS.clear()
        jobs.REQUESTS.clear()

    def test_same_id_is_atomic_under_concurrent_acceptance(self):
        metadata, audio = meta(), wav()
        with ThreadPoolExecutor(max_workers=4) as pool:
            replies = list(pool.map(lambda _: jobs.ingest({"file": audio}, metadata), range(4)))
        self.assertEqual(len({r[0]["jobId"] for r in replies}), 1)
        self.assertEqual(sum(r[1] for r in replies), 1)
        self.assertEqual(len(list(Path(self.tmp.name).glob("*/file.wav"))), 1)

    def test_same_id_cannot_change_audio_or_session_metadata(self):
        metadata = meta()
        jobs.ingest({"file": wav()}, metadata)
        with self.assertRaises(HTTPException) as caught:
            jobs.ingest({"file": wav()}, {**metadata, "sessionId": 99})
        self.assertEqual(caught.exception.status_code, 409)

    def test_low_bandwidth_timbre_and_broken_wav_are_rejected_without_orphans(self):
        for audio in (wav(16000), b"not wav"):
            with self.assertRaises(HTTPException):
                jobs.ingest({"file": audio}, meta())
        self.assertEqual(list(Path(self.tmp.name).iterdir()), [])

    def test_range_accepts_16khz_without_timbre_models(self):
        reply, created = jobs.ingest({"file": wav(16000)}, meta("RANGE"))
        self.assertTrue(created)
        self.assertEqual(list(jobs.JOBS[reply["jobId"]]["components"]), ["RANGE"])

    def test_restart_restores_request_identity_and_queued_audio(self):
        metadata = meta()
        reply, _ = jobs.ingest({"file": wav()}, metadata)
        jobs._update(reply["jobId"], status="RUNNING")
        jobs.JOBS.clear()
        jobs.REQUESTS.clear()
        jobs.restore_jobs()
        duplicate, created = jobs.ingest({"file": wav()}, metadata)
        self.assertFalse(created)
        self.assertEqual(duplicate["jobId"], reply["jobId"])
        self.assertEqual(duplicate["status"], "QUEUED")

    def test_callback_outbox_survives_restart_and_only_then_starts_soft_deletion(self):
        reply, _ = jobs.ingest({"file": wav()}, meta())
        identifier = reply["jobId"]
        payloads = {c: jobs._failure_callback(jobs.JOBS[identifier], c, "FAILED", "TEST", "test")
                    for c in ("ECAPA", "SINGER_BYOL")}
        with (mock.patch.object(jobs, "_timbre_callbacks", return_value=payloads),
              mock.patch.object(jobs, "post_callback", return_value={"accepted": False, "http": 503})):
            jobs.process(identifier)
        self.assertEqual(jobs.JOBS[identifier]["status"], "CALLBACK_PENDING")
        self.assertEqual(jobs.JOBS[identifier]["componentStatus"], "FAILED")
        self.assertIsNone(jobs.JOBS[identifier]["softExpiresAt"])
        self.assertTrue((Path(self.tmp.name) / identifier / "callback.ECAPA.json").is_file())
        jobs.JOBS.clear()
        jobs.REQUESTS.clear()
        jobs.restore_jobs()
        self.assertEqual(jobs.JOBS[identifier]["status"], "CALLBACK_PENDING")
        jobs._update(identifier, nextCallbackAt=None)
        with mock.patch.object(jobs, "post_callback", return_value={"accepted": True, "http": 200}) as send:
            jobs.deliver_callbacks(identifier)
            self.assertEqual(send.call_count, 2)
        self.assertIsNotNone(jobs.JOBS[identifier]["softExpiresAt"])
        self.assertEqual(jobs.JOBS[identifier]["status"], "FAILED")
        self.assertTrue(all(c["accepted"] for c in jobs.JOBS[identifier]["components"].values()))
        with mock.patch.object(jobs, "_save") as save:
            jobs.deliver_callbacks(identifier)
            save.assert_not_called()
        jobs.sweep(datetime.now() + timedelta(days=2))
        self.assertFalse((Path(self.tmp.name) / identifier / "file.wav").exists())

    def test_duplicate_pending_request_delivers_only_unaccepted_callback_without_inference(self):
        metadata, audio = meta(), wav()
        reply, _ = jobs.ingest({"file": audio}, metadata)
        identifier = reply["jobId"]
        payloads = {c: jobs._failure_callback(jobs.JOBS[identifier], c, "COMPLETED", None, "")
                    for c in ("ECAPA", "SINGER_BYOL")}
        with (mock.patch.object(jobs, "_timbre_callbacks", return_value=payloads),
              mock.patch.object(jobs, "post_callback", side_effect=[
                  {"accepted": True, "http": 200}, {"accepted": False, "http": 503}])):
            jobs.process(identifier)
        self.assertEqual(jobs.JOBS[identifier]["status"], "CALLBACK_PENDING")
        app = FastAPI()
        app.include_router(jobs.router)
        with (mock.patch.dict(os.environ, MICON_BE_CALLBACK_URL="http://127.0.0.1/callback"),
              mock.patch.object(jobs, "_configuration_status", return_value=({}, [])),
              mock.patch.object(jobs, "process") as infer,
              mock.patch.object(jobs, "post_callback", return_value={"accepted": True, "http": 200}) as send):
            response = TestClient(app).post("/voice-analysis-jobs", files={
                "file": ("recording.wav", audio, "audio/wav"),
                "meta": ("meta.json", json.dumps(metadata), "application/json")})
        self.assertEqual(response.status_code, 202, response.text)
        self.assertEqual(response.json()["jobId"], identifier)
        self.assertEqual(response.json()["status"], "COMPLETED")
        self.assertTrue(response.json()["duplicate"])
        infer.assert_not_called()
        send.assert_called_once()
        self.assertEqual(send.call_args.args[0]["component"], "SINGER_BYOL")
        self.assertEqual(jobs.JOBS[identifier]["status"], "COMPLETED")
        self.assertIsNotNone(jobs.JOBS[identifier]["softExpiresAt"])

    def test_cancel_during_callback_delivery_does_not_restore_completed_status(self):
        reply, _ = jobs.ingest({"file": wav()}, meta())
        identifier = reply["jobId"]
        payloads = {c: jobs._failure_callback(jobs.JOBS[identifier], c, "COMPLETED", None, "")
                    for c in ("ECAPA", "SINGER_BYOL")}
        def cancel_while_delivering(_):
            jobs.cancel_job(identifier)
            return {"accepted": True, "http": 200}
        with (mock.patch.object(jobs, "_timbre_callbacks", return_value=payloads),
              mock.patch.object(jobs, "post_callback", side_effect=cancel_while_delivering)):
            jobs.process(identifier)
        self.assertEqual(jobs.JOBS[identifier]["status"], "CANCELED")
        self.assertTrue(jobs.JOBS[identifier]["audioDeleted"])
        self.assertIsNone(jobs.JOBS[identifier]["softExpiresAt"])

    def test_catalog_refresh_uses_direct_backend_path_and_service_token(self):
        previous = dict(catalog_link._CACHE)
        self.addCleanup(catalog_link._CACHE.update, previous)
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080/", AI_SERVICE_TOKEN="test-token"),
              mock.patch.object(catalog_link, "_get", return_value={"data": {
                  "revision": "r1", "batch_id": "batch-1",
                  "tracks": [{"catalog_track_id": "tj-00281", "song_id": 412}]}}) as fetch):
            state = catalog_link.refresh()
        fetch.assert_called_once_with("http://backend:8080/internal/embedding-catalog/active", "test-token")
        self.assertIsNone(state["error"])
        self.assertEqual(catalog_link.song_ids(), {"tj-00281": 412, "412": 412})

    def test_canceled_queued_job_does_not_start_inference(self):
        reply, _ = jobs.ingest({"file": wav()}, meta())
        jobs.cancel_job(reply["jobId"])
        with mock.patch.object(jobs, "_timbre_callbacks") as infer:
            jobs.process(reply["jobId"])
        infer.assert_not_called()
        self.assertTrue(jobs.JOBS[reply["jobId"]]["audioDeleted"])

    def test_multipart_file_and_json_blob_match_the_be_http_contract(self):
        app = FastAPI()
        app.include_router(jobs.router, dependencies=[Depends(require_service_token)])
        with (mock.patch.dict(os.environ, AI_SERVICE_TOKEN="test-service-token", MICON_BE_CALLBACK_URL="http://127.0.0.1/callback"),
              mock.patch.object(jobs, "_configuration_status", return_value=({}, [])),
              mock.patch.object(jobs, "process") as process):
            client = TestClient(app)
            self.assertEqual(client.post("/voice-analysis-jobs").status_code, 401)
            response = client.post("/voice-analysis-jobs", headers={"Authorization": "Bearer test-service-token"},
                files={"file": ("recording.wav", wav(), "audio/wav"),
                       "meta": ("meta.json", json.dumps(meta()), "application/json")})
            self.assertEqual(response.status_code, 202, response.text)
            self.assertEqual(response.json()["status"], "QUEUED")
            self.assertIn("audioExpiresAt", response.json())
            process.assert_called_once()

    def test_be_embedding_objects_keep_segment_vectors_and_dimensions(self):
        matrices = recommendations.user_embedding_matrices(embeddings())
        self.assertEqual(matrices["ecapa"].shape, (1, 192))
        self.assertEqual(matrices["singer_byol"].shape, (1, 1000))
        for matrix in matrices.values():
            np.testing.assert_allclose(np.linalg.norm(matrix, axis=1), 1)

    def test_zero_missing_or_ragged_embeddings_are_rejected(self):
        values = embeddings()
        values["ECAPA"]["segmentInfo"]["embeddings"] = [[0.0] * 192]
        with self.assertRaises(HTTPException):
            recommendations.user_embedding_matrices(values)
        with self.assertRaises(HTTPException):
            recommendations.user_embedding_matrices({"ECAPA": embeddings()["ECAPA"]})
        values = embeddings()
        values["ECAPA"]["segmentInfo"]["embeddings"] = [[1.0], [1.0, 2.0]]
        with self.assertRaises(HTTPException):
            recommendations.user_embedding_matrices(values)

    def test_operational_recommendation_requires_explicit_db_id_mapping(self):
        with (mock.patch.object(recommendations, "_configuration_status", return_value=({}, [])),
              mock.patch.object(catalog_link, "song_ids", return_value={})):
            with self.assertRaises(HTTPException) as caught:
                recommendations._create_from_json({"userEmbedding": embeddings(), "mode": "timbre",
                    "profileId": 10, "topK": 10}, BackgroundTasks())
        self.assertEqual(caught.exception.status_code, 503)

    def test_numeric_catalog_id_is_not_inferred_from_a_tj_number(self):
        self.assertEqual(song_id_of({"song_id": 412, "tj_id": "TJ-00281"}), 412)
        self.assertEqual(song_id_of({"tj_id": "TJ-00281"}), "tj-00281")
        self.assertIsNone(song_id_of({"catalog_track_id": "412"}))


if __name__ == "__main__":
    unittest.main()
