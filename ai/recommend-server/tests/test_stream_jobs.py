from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient
import numpy as np
import soundfile as sf

import analysis_jobs
import stream_jobs
from timbre_recommender.embedding import embed_vocal_audio, load_vocal_windows


class StreamJobsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patch = mock.patch.object(analysis_jobs, "ANALYSIS_DIR", Path(self.tmp.name))
        patch.start()
        self.addCleanup(patch.stop)
        analysis_jobs.JOBS.clear()
        analysis_jobs.REQUESTS.clear()
        app = FastAPI()
        app.include_router(stream_jobs.router)
        self.client = TestClient(app)
        self.stream_id = str(uuid.uuid4())
        self.session_id = 7
        self.rate = 44100
        self.chunk = b"\x00\x10" * (self.rate * 2)

    def begin(self):
        return self.client.post(f"/voice-analysis-streams/{self.stream_id}",
                                json={"sessionId": self.session_id, "sampleRate": self.rate})

    def send(self, index: int, data: bytes | None = None):
        return self.client.put(f"/voice-analysis-streams/{self.stream_id}/chunks/{index}",
                               params={"sessionId": self.session_id},
                               content=self.chunk if data is None else data)

    def test_resume_and_duplicate_are_idempotent(self):
        self.assertEqual(self.begin().status_code, 200)
        self.assertEqual(self.send(0).json()["nextIndex"], 1)
        self.assertEqual(self.send(0).json()["nextIndex"], 1)
        self.assertEqual(self.send(0, b"\x01\x10" * (self.rate * 2)).status_code, 409)
        self.assertEqual(self.send(2).status_code, 409)
        self.assertEqual(self.client.get(f"/voice-analysis-streams/{self.stream_id}",
                                         params={"sessionId": self.session_id}).json()["nextIndex"], 1)
        self.assertEqual(self.client.get(f"/voice-analysis-streams/{self.stream_id}",
                                         params={"sessionId": 8}).status_code, 404)

    def test_complete_constructs_wav_and_keeps_existing_analysis_contract(self):
        self.begin()
        for index in range(5):
            self.assertEqual(self.send(index).status_code, 200)
        pcm = self.chunk * 5
        digest = hashlib.sha256(pcm).hexdigest()
        inspected = self.client.post(f"/voice-analysis-streams/{self.stream_id}/inspect",
                                     json={"sessionId": self.session_id, "pcmSha256": digest})
        self.assertEqual(inspected.json()["durationMs"], 10000)
        meta = {"requestId": self.stream_id, "sessionId": self.session_id,
                "recording": {"sampleRate": self.rate}}
        with mock.patch.object(analysis_jobs, "ingest", return_value=({"jobId": "job-1"}, False)) as ingest:
            response = self.client.post(f"/voice-analysis-streams/{self.stream_id}/complete",
                                        json={"meta": meta, "pcmSha256": digest})
        self.assertEqual(response.status_code, 202)
        audio = ingest.call_args.args[0]["file"]
        self.assertEqual(audio[:4], b"RIFF")
        self.assertEqual(audio[44:], pcm)
        self.assertEqual(self.send(5).status_code, 409)

    def test_missing_audio_never_commits(self):
        self.begin()
        self.send(0)
        meta = {"requestId": self.stream_id, "sessionId": self.session_id,
                "recording": {"sampleRate": self.rate}}
        with mock.patch.object(analysis_jobs, "ingest") as ingest:
            response = self.client.post(f"/voice-analysis-streams/{self.stream_id}/complete",
                                        json={"meta": meta, "pcmSha256": "bad"})
        self.assertEqual(response.status_code, 409)
        ingest.assert_not_called()

    def test_complete_is_idempotent_with_the_real_job_store(self):
        self.begin()
        for index in range(5):
            self.send(index)
        digest = hashlib.sha256(self.chunk * 5).hexdigest()
        meta = {"requestId": self.stream_id, "analysisId": 12, "sessionId": self.session_id,
                "attemptNo": 1, "analysisType": "TIMBRE",
                "components": ["ECAPA", "SINGER_BYOL"], "contractVersion": "v1",
                "recording": {"sampleRate": self.rate}}
        with mock.patch.object(analysis_jobs, "process"):
            first = self.client.post(f"/voice-analysis-streams/{self.stream_id}/complete",
                                     json={"meta": meta, "pcmSha256": digest})
            second = self.client.post(f"/voice-analysis-streams/{self.stream_id}/complete",
                                      json={"meta": meta, "pcmSha256": digest})
        self.assertEqual(first.status_code, 202)
        self.assertEqual(second.status_code, 202)
        self.assertFalse(first.json()["duplicate"])
        self.assertTrue(second.json()["duplicate"])
        self.assertEqual(first.json()["jobId"], second.json()["jobId"])

    def test_precomputed_windows_preserve_final_vectors_and_selection(self):
        class Runtime:
            device = "cpu"
            ecapa_checkpoint_sha256 = "a" * 64
            singer_checkpoint_sha256 = "b" * 64
            checked_out_singer_revision = "test"

            def require_compatible(self, **_):
                return None

            def embed_windows(self, windows, *, timings=None):
                values = windows.mean(axis=1)
                return (np.repeat(values[:, None], 192, axis=1).astype(np.float32),
                        np.repeat((values * 2)[:, None], 1000, axis=1).astype(np.float32))

        root = Path(self.tmp.name)
        rate = 44100
        t = np.arange(rate * 10) / rate
        signal = (.2 * np.sin(2 * np.pi * (220 * t + 3 * t * t))).astype(np.float32)
        source = root / "source.wav"
        sf.write(source, signal, rate, subtype="PCM_16")
        runtime = Runtime()
        before = embed_vocal_audio(source, root / "before", ecapa_checkpoint=source,
            singer_repo=root, singer_checkpoint_root=root, runtime=runtime, min_segments=4)
        windows, _ = load_vocal_windows(source, min_segments=4)
        early_ecapa, early_byol = runtime.embed_windows(windows[:2])
        cached = {hashlib.sha256(window.tobytes()).hexdigest():
                  (early_ecapa[index], early_byol[index]) for index, window in enumerate(windows[:2])}
        timings = {}
        after = embed_vocal_audio(source, root / "after", ecapa_checkpoint=source,
            singer_repo=root, singer_checkpoint_root=root, runtime=runtime, min_segments=4,
            cached_embeddings=cached, timings=timings)
        self.assertEqual(timings["precomputedSegments"], 2)
        self.assertEqual(before["segments"], after["segments"])
        np.testing.assert_allclose(np.load(root / "before.ecapa.npy"), np.load(root / "after.ecapa.npy"))
        np.testing.assert_allclose(np.load(root / "before.singer_byol.npy"), np.load(root / "after.singer_byol.npy"))

    def test_fifth_chunk_precomputes_reusable_four_second_windows(self):
        class Runtime:
            ecapa_checkpoint_sha256 = "a" * 64
            singer_checkpoint_sha256 = "b" * 64

            def embed_windows(self, windows):
                values = windows.mean(axis=1)
                return (np.repeat(values[:, None], 192, axis=1).astype(np.float32),
                        np.repeat(values[:, None], 1000, axis=1).astype(np.float32))

        self.begin()
        t = np.arange(self.rate * 10) / self.rate
        pcm = np.round(.2 * np.sin(2 * np.pi * (220 * t + 3 * t * t)) * 32767)
        audio = pcm.astype("<i2").tobytes()
        runtime = Runtime()
        with (mock.patch.object(analysis_jobs, "_configuration_status", return_value=({}, [])),
              mock.patch.object(analysis_jobs, "get_timbre_runtime", return_value=runtime)):
            for index in range(5):
                start = index * self.rate * 4
                self.assertEqual(self.send(index, audio[start:start + self.rate * 4]).status_code, 200)
        self.assertGreaterEqual(len(stream_jobs.load_precomputed(self.stream_id, runtime)), 4)
        with mock.patch.object(analysis_jobs, "build_sha", return_value="different-build"):
            self.assertEqual(stream_jobs.load_precomputed(self.stream_id, runtime), {})


if __name__ == "__main__":
    unittest.main()
