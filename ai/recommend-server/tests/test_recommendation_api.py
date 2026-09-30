from __future__ import annotations

from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import numpy as np
from fastapi import HTTPException

import recommendation_api


class RecommendationApiTestCase(unittest.TestCase):
    def test_voice_recommendation_uses_the_approved_score_weights(self) -> None:
        config = recommendation_api.VOICE_RANKER_CONFIG
        self.assertAlmostEqual(config.base_ecapa_weight, .70 / .85)
        self.assertEqual(config.centroid_weight, .50)
        self.assertEqual(config.segment_weight, .50)

    def test_measured_quality_blends_model_scores_in_the_public_response(self) -> None:
        query = {}
        vectors = {}
        for key, dimension, cosine in (("ecapa", 192, .8), ("singer_byol", 1000, .4)):
            query[key] = np.zeros((1, dimension), dtype=np.float32)
            query[key][0, 0] = 1
            vectors[key] = np.zeros((1, dimension), dtype=np.float32)
            vectors[key][0, :2] = [cosine, np.sqrt(1 - cosine ** 2)]
        track = SimpleNamespace(track_id="track", title="Synthetic Song", artist="Synthetic Artist",
                                metadata={"song_id": "song-1"})
        catalog = SimpleNamespace(tracks=[track], embeddings=lambda track, key: vectors[key])
        histogram = [0.0] * 60
        histogram[60 - 36] = 1
        ranges = SimpleNamespace(profiles={"track": {"histogram": histogram, "coreLowMidi": 60,
                                                   "coreHighMidi": 60, "medianMidi": 60,
                                                   "reliable": True, "confidence": 1}})
        mapping = SimpleNamespace(by_track={"track": 1},
                                  preferred_ids=lambda names, catalog: [])
        with mock.patch.object(recommendation_api.ArtistCatalog, "load", return_value=mapping):
            result = recommendation_api.build_result(catalog, ranges, query=query, pitch_profile=None,
                    pitch_error=None, mode="timbre", top_k=15,
                    voice_quality={"version": "personal-acoustic-v1", "points": [{"midi": 60, "score": 60}]})
        item = result["shelves"][0]["items"][0]
        self.assertAlmostEqual(item["final_score"], .70 * .8 + .15 * .4 + .15 * .6, places=6)
        self.assertEqual(item["recommended_key"], 0)
        self.assertEqual(item["metrics"]["stability"]["measuredCoverage"], 1)
        self.assertEqual(result["algorithm"]["applied_mode"], "stability_soft_score")
        self.assertFalse(result["algorithm"]["range_enabled"])
        self.assertEqual(result["algorithm"]["scoring_version"], "voice-stability-70-15-15-v1")

    def test_operational_readiness_requires_backend_catalog_mapping(self) -> None:
        catalog = SimpleNamespace(tracks=[])
        with (
            mock.patch.object(recommendation_api, "_configuration_status",
                              return_value=({"index_dir": "index"}, [])),
            mock.patch.object(recommendation_api.CatalogIndex, "load", return_value=catalog),
            mock.patch.object(recommendation_api.RangeCatalog, "load"),
            mock.patch.object(recommendation_api.ArtistCatalog, "load", return_value=SimpleNamespace(
                by_track={}, catalog_version="test", source_revision=1)),
            mock.patch.object(recommendation_api.catalog_link, "ensure",
                              return_value={"mappedTracks": 0, "revision": None,
                                            "batchId": None, "error": "backend starting"}),
        ):
            status = recommendation_api.recommendation_status()
        self.assertTrue(status["engineReady"])
        self.assertFalse(status["ready"])
        self.assertEqual(status["catalogLink"]["mappedTracks"], 0)

    def test_auto_device_resolves_to_cuda_when_available(self) -> None:
        with mock.patch("torch.cuda.is_available", return_value=True):
            self.assertEqual(recommendation_api._resolved_device("auto"), "cuda")

    def test_wav_conversion_caps_decode_and_rejects_audio_over_two_minutes(self) -> None:
        completed = SimpleNamespace(returncode=0, stderr="")
        with mock.patch.object(
            recommendation_api.subprocess, "run", return_value=completed
        ) as run, mock.patch.object(
            recommendation_api.sf, "info", return_value=SimpleNamespace(duration=121.0)
        ):
            with self.assertRaisesRegex(RuntimeError, "2분 이하"):
                recommendation_api._convert_to_wav(Path("input.mp3"), Path("output.wav"))
        command = run.call_args.args[0]
        self.assertIn("-t", command)
        self.assertEqual(command[command.index("-t") + 1], "121.0")

    def test_wav_conversion_accepts_audio_at_duration_limit(self) -> None:
        completed = SimpleNamespace(returncode=0, stderr="")
        with mock.patch.object(
            recommendation_api.subprocess, "run", return_value=completed
        ), mock.patch.object(
            recommendation_api.sf, "info", return_value=SimpleNamespace(duration=120.0)
        ):
            recommendation_api._convert_to_wav(Path("input.mp3"), Path("output.wav"))

    def test_atomic_metadata_replace_retries_a_brief_windows_lock(self) -> None:
        with mock.patch.object(
            recommendation_api.os,
            "replace",
            side_effect=(PermissionError("locked"), None),
        ) as replace, mock.patch.object(recommendation_api.time, "sleep") as sleep:
            recommendation_api._replace_with_retry(Path("source"), Path("destination"))
        self.assertEqual(replace.call_count, 2)
        sleep.assert_called_once()

    def test_invalid_and_unknown_job_ids_are_distinguished(self) -> None:
        with self.assertRaises(HTTPException) as invalid:
            recommendation_api.get_recommendation("not-a-job")
        self.assertEqual(invalid.exception.status_code, 400)

        with self.assertRaises(HTTPException) as unknown:
            recommendation_api.get_recommendation("00000000-0000-4000-8000-000000000000")
        self.assertEqual(unknown.exception.status_code, 404)

    def test_query_cleanup_preserves_only_non_query_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in (
                "original.wav",
                "normalized.wav",
                "query.ecapa.npy",
                "query.singer_byol.npy",
                "query.segments.json",
                "job.json",
            ):
                (root / name).write_bytes(b"test")
            recommendation_api._cleanup_query_artifacts(root)
            self.assertEqual([path.name for path in root.iterdir()], ["job.json"])


if __name__ == "__main__":
    unittest.main()
