from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
from unittest import mock

import numpy as np
import soundfile as sf

import analysis_jobs
from mr_detection import detect_mr_suspected
import separation


class MrDetectionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _write(self, name: str, signal: np.ndarray, sample_rate: int = 16_000) -> Path:
        path = Path(self.tmp.name) / name
        sf.write(path, signal, sample_rate, subtype="PCM_16")
        return path

    @staticmethod
    def _tone(seconds: int, sample_rate: int, frequency: float, amplitude: float) -> np.ndarray:
        samples = np.arange(seconds * sample_rate, dtype=np.float32)
        return amplitude * np.sin(2 * np.pi * frequency * samples / sample_rate)

    def test_clean_recording_is_not_routed_to_separation(self) -> None:
        sample_rate, seconds = 16_000, 16
        clean = np.zeros(sample_rate * seconds, dtype=np.float32)
        vocal = self._tone(2, sample_rate, 220, 0.25)
        for start_second in (0, 4, 8, 12):
            start = start_second * sample_rate
            clean[start : start + len(vocal)] = vocal
        result = detect_mr_suspected(self._write("clean.wav", clean))
        self.assertEqual(result["status"], "NOT_SUSPECTED")
        self.assertFalse(result["requiresSeparation"])

    def test_continuous_accompaniment_is_routed_to_separation(self) -> None:
        sample_rate, seconds = 16_000, 16
        mr = self._tone(seconds, sample_rate, 110, 0.07)
        vocal = self._tone(2, sample_rate, 220, 0.14)
        for start_second in (0, 4, 8, 12):
            start = start_second * sample_rate
            mr[start : start + len(vocal)] += vocal
        result = detect_mr_suspected(self._write("mr.wav", mr))
        self.assertEqual(result["status"], "SUSPECTED")
        self.assertEqual(result["displayLabel"], "MR 의심")
        self.assertTrue(result["requiresSeparation"])

    def test_prepare_separation_input_converts_to_44k1_pcm(self) -> None:
        source = self._write("48k.wav", self._tone(2, 48_000, 220, 0.2), 48_000)
        output = Path(self.tmp.name) / "separation-input.wav"
        analysis_jobs._prepare_separation_input(source, output)
        info = sf.info(output)
        self.assertEqual(info.samplerate, 44_100)
        self.assertEqual(info.subtype, "PCM_16")

    def test_callback_model_version_records_preprocessing(self) -> None:
        job = {
            "jobId": "job_" + "0" * 32,
            "requestId": "request",
            "meta": {"attemptNo": 1},
            "files": {"file": {"durationSeconds": 10.0}},
            "components": {"ECAPA": {}, "SINGER_BYOL": {}},
        }
        metadata = {
            "models": {"ecapa": {"checkpoint_sha256": "ecapa"}, "singer_byol": {"checkpoint_sha256": "byol"}},
            "segments": [{"start_seconds": float(index), "active_frame_fraction": 0.7, "clipped_fraction": 0.0}
                         for index in range(5)],
            "window_seconds": 4.0,
        }
        callbacks = analysis_jobs.build_callbacks(
            job, np.ones((5, 192)), np.ones((5, 1000)), metadata, "sep-melband-kim-v1")
        self.assertEqual(callbacks["ECAPA"]["modelVersion"], "ecapa+sep-melband-kim-v1")
        self.assertEqual(callbacks["SINGER_BYOL"]["modelVersion"], "byol+sep-melband-kim-v1")
        self.assertEqual(callbacks["ECAPA"]["segmentInfo"]["validChunkCount"], 5)
        self.assertEqual(callbacks["SINGER_BYOL"]["segmentInfo"]["validChunkCount"], 5)

    def test_callback_never_emits_fewer_chunks_than_backend_accepts(self) -> None:
        job = {
            "jobId": "job_" + "0" * 32,
            "requestId": "request",
            "meta": {"attemptNo": 1},
            "files": {"file": {"durationSeconds": 10.0}},
            "components": {"ECAPA": {}, "SINGER_BYOL": {}},
        }
        metadata = {
            "models": {"ecapa": {"checkpoint_sha256": "ecapa"}, "singer_byol": {"checkpoint_sha256": "byol"}},
            "segments": [{"start_seconds": float(index), "active_frame_fraction": 0.7, "clipped_fraction": 0.0}
                         for index in range(3)],
            "window_seconds": 4.0,
        }
        with self.assertRaisesRegex(ValueError, "segment count"):
            analysis_jobs.build_callbacks(
                job, np.ones((3, 192)), np.ones((3, 1000)), metadata)

    def test_user_analysis_callback_accepts_four_chunks_for_both_guides(self) -> None:
        job = {
            "jobId": "job_" + "0" * 32,
            "requestId": "request",
            "meta": {"analysisType": "TIMBRE", "attemptNo": 1,
                     "recordingMetadata": {"guideVersion": "holo-v1"}},
            "files": {"file": {"durationSeconds": 10.0}},
            "components": {"ECAPA": {}, "SINGER_BYOL": {}},
        }
        metadata = {
            "models": {"ecapa": {"checkpoint_sha256": "ecapa"},
                       "singer_byol": {"checkpoint_sha256": "byol"}},
            "segments": [{"start_seconds": float(index * 2), "active_frame_fraction": 0.7,
                          "clipped_fraction": 0.0} for index in range(4)],
            "window_seconds": 4.0,
        }
        for guide_version in ("holo-v1", "v4"):
            with self.subTest(guide_version=guide_version):
                job["meta"]["recordingMetadata"]["guideVersion"] = guide_version
                callbacks = analysis_jobs.build_callbacks(
                    job, np.ones((4, 192)), np.ones((4, 1000)), metadata)
                self.assertEqual(callbacks["ECAPA"]["segmentInfo"]["validChunkCount"], 4)
                self.assertEqual(callbacks["SINGER_BYOL"]["segmentInfo"]["validChunkCount"], 4)

    def test_strict_user_separation_never_uses_legacy_fallback(self) -> None:
        with (mock.patch.object(separation, "_separate_melband", side_effect=RuntimeError("broken")),
              mock.patch.object(separation, "_separate_htdemucs") as fallback):
            with self.assertRaisesRegex(RuntimeError, "Mel-Band 보컬 분리 실패"):
                separation.separate(Path("input.wav"), Path(self.tmp.name), "cpu", allow_fallback=False)
        fallback.assert_not_called()

    def test_user_analysis_requests_four_windows_from_embedding_pipeline(self) -> None:
        job = {"jobId": "job_" + "1" * 32,
               "meta": {"analysisType": "TIMBRE", "recordingMetadata": {"guideVersion": "v4"}}}
        config = {"ecapa_checkpoint": "ecapa.ckpt", "singer_repo": "singer-repo",
                  "singer_checkpoint_root": "singer-root", "device": "cpu"}
        with (
            mock.patch.object(analysis_jobs, "_limit_torch_threads"),
            mock.patch.object(analysis_jobs, "detect_mr_suspected", return_value={"requiresSeparation": False}),
            mock.patch.object(analysis_jobs, "_update"),
            mock.patch.object(analysis_jobs, "get_timbre_runtime", return_value=mock.Mock(device="cpu")),
            mock.patch.object(analysis_jobs, "embed_vocal_audio", return_value={}) as embed_call,
            mock.patch.object(analysis_jobs.np, "load", side_effect=[np.ones((4, 192)), np.ones((4, 1000))]),
            mock.patch.object(analysis_jobs, "build_callbacks", return_value={"ok": True}),
        ):
            analysis_jobs._timbre_callbacks(job, Path("recording.wav"), Path("query"), config)
        self.assertEqual(embed_call.call_args.kwargs["min_segments"], 4)

    def test_timbre_separation_failure_keeps_raw_embedding_path(self) -> None:
        job = {"jobId": "job_" + "1" * 32}
        audio = Path(self.tmp.name) / "recording.wav"
        prefix = str(Path(self.tmp.name) / "embedding")
        config = {
            "ecapa_checkpoint": "ecapa.ckpt",
            "singer_repo": "singer-repo",
            "singer_checkpoint_root": "singer-root",
            "device": "cpu",
        }
        detection = {"requiresSeparation": True, "status": "SUSPECTED"}
        with (
            mock.patch.object(analysis_jobs, "_limit_torch_threads"),
            mock.patch.object(analysis_jobs, "detect_mr_suspected", return_value=detection),
            mock.patch.object(analysis_jobs, "_prepare_separation_input"),
            mock.patch.object(analysis_jobs, "separate", side_effect=RuntimeError("broken")) as separate_call,
            mock.patch.object(analysis_jobs, "_update") as update_call,
            mock.patch.object(analysis_jobs, "get_timbre_runtime",
                              return_value=mock.Mock(device="cpu")),
            mock.patch.object(analysis_jobs, "embed_vocal_audio", return_value={}) as embed_call,
            mock.patch.object(analysis_jobs.np, "load", side_effect=[np.ones((1, 192)), np.ones((1, 1000))]),
            mock.patch.object(analysis_jobs, "build_callbacks", return_value={"ok": True}),
        ):
            result = analysis_jobs._timbre_callbacks(job, audio, prefix, config)

        self.assertEqual(result, {"ok": True})
        separate_call.assert_called_once()
        self.assertEqual(update_call.call_count, 2)
        self.assertEqual(embed_call.call_args.kwargs["min_segments"], 4)
        recorded = update_call.call_args_list[0].kwargs["mrDetection"]
        self.assertEqual(recorded["separation"]["status"], "FAILED")
        self.assertEqual(recorded["embeddingInput"], "raw-recording")
        self.assertEqual(recorded["preprocessingVersion"], "raw-clean-v1")
        self.assertIs(embed_call.call_args.args[0], audio)


if __name__ == "__main__":
    unittest.main()
