"""Opt-in test with real, locally installed CREPE weights (no model download)."""
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@unittest.skipUnless(os.environ.get("RUN_RANGE_MODEL_TESTS") == "1", "set RUN_RANGE_MODEL_TESTS=1 to run real model inference")
class ModelIntegrationTests(unittest.TestCase):
    def test_absolute_pitch_is_preserved_across_two_attempts(self):
        import numpy as np
        import soundfile as sf
        from range_models import Submission
        from fastapi.testclient import TestClient
        sr = 16000
        y = np.zeros(8 * sr, np.float32)
        attempts = []
        for i, cents in enumerate((0, 35)):
            offset = i * 4
            t = np.arange(3 * sr) / sr
            hz = 440 * 2 ** ((60 - 69 + cents / 100) / 12)
            voice = (.25 * np.sin(2*np.pi*hz*t) + .08*np.sin(4*np.pi*hz*t) + .04*np.sin(6*np.pi*hz*t)).astype(np.float32)
            y[(offset+1)*sr:(offset+4)*sr] = voice
            attempts.append(dict(attemptId=str(i), targetMidi=60, direction="anchor" if i == 0 else "up", cueStart=offset,
                                 cueEnd=offset+.5, responseStart=offset+.75, responseEnd=offset+4,
                                 evaluationStart=offset+1.4, evaluationEnd=offset+3.4,
                                 clientStatus="passed" if i == 0 else "not_passed"))
        s = Submission.model_validate(dict(sessionId="model-smoke", measurementType="guided_range", protocolVersion="guided-range-v1",
            noiseRms=.0001, durationSeconds=8, startMidi=60, stopReason="user_stopped", lowerBoundaryStatus="not_probed",
            upperBoundaryStatus="user_stopped", attempts=attempts))
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "test.wav"
            sf.write(path, y, sr, subtype="PCM_16")
            import range_preview_app as preview
            with patch.object(preview, "STORAGE", Path(d) / "preview"):
                response = TestClient(preview.app, client=("127.0.0.1", 50000)).post("/preview/guided-range",
                    files={"audio": ("test.wav", path.read_bytes(), "audio/wav")},
                    data={"metadata": s.model_copy(update={"sessionId": str(uuid4())}).model_dump_json(by_alias=True)})
                self.assertEqual(200, response.status_code, response.text[:1000])
                self.assertEqual("model_verified", response.json()["verification"])
                report = response.json()
        results = report["attemptResults"]
        self.assertEqual("passed", results[0]["status"], results)
        self.assertEqual("not_passed", results[1]["status"], results)
        self.assertGreater(results[1]["metrics"]["medianAbsErrorCents"], 25)
        self.assertTrue(any(f["voiced"] and f["periodicity"] > .62 and f["rawMidi"] is not None
                            for f in results[0]["frames"]))
        self.assertEqual(0, report["calibration"]["tuningCorrectionCents"])


if __name__ == "__main__": unittest.main()
