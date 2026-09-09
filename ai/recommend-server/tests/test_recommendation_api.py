from __future__ import annotations

from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

from fastapi import HTTPException

import recommendation_api


class RecommendationApiTestCase(unittest.TestCase):
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
