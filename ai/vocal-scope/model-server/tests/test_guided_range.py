import json
import math
import sys
import unittest
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from range_evaluator import evaluate, passes, build_report
from range_models import Submission


class RangeTests(unittest.TestCase):
    def test_shared_fixtures(self):
        cases = json.loads((ROOT.parents[2] / "tests/fixtures/guided-range.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(case=case["name"]):
                frames = []
                for i in range(100):
                    t = i * .02
                    segment = next((s for s in case["segments"] if s["start"] - 1e-9 <= t < s["end"] - 1e-9), None)
                    if segment:
                        frames.append(dict(t=t, duration=.02, f0=440 * 2 ** ((case["target"] - 69 + segment["cents"] / 100) / 12),
                                           clarity=segment.get("clarity", 1), rmsLow=segment.get("rms", .1), clippedRatio=segment.get("clip", 0)))
                m = evaluate(frames, case["target"], 0, 2, .001)
                self.assertEqual(case["passed"], passes(m))
                if "expectedHold" in case:
                    self.assertAlmostEqual(case["expectedHold"], m["longestHoldSeconds"])

    def test_gaps_do_not_fill_the_stable_range(self):
        meta = dict(stopReason="time_limit", lowerBoundaryStatus="three_failures", upperBoundaryStatus="time_limit")
        results = [dict(targetMidi=m, status=s, metrics=None) for m, s in [(60, "passed"), (62, "passed"), (64, "needs_review"), (66, "passed"), (66, "not_passed")]]
        report = build_report(meta, results)
        self.assertEqual([[60, 60], [62, 62]], report["stableIntervals"])
        statuses = {n["midi"]: n["status"] for n in report["notes"]}
        self.assertEqual("unmeasured", statuses[61])
        self.assertEqual("needs_review", statuses[64])
        self.assertEqual("inconsistent", statuses[66])

    def test_invalid_window_is_rejected(self):
        data = dict(sessionId="test", measurementType="guided_range", protocolVersion="guided-range-v1", startMidi=60,
                    noiseRms=.0001, durationSeconds=5, stopReason="user_stopped", lowerBoundaryStatus="not_probed",
                    upperBoundaryStatus="not_probed", attempts=[dict(attemptId="1", targetMidi=60, direction="anchor", cueStart=0,
                        cueEnd=.5, responseStart=.75, responseEnd=3, evaluationStart=.4, evaluationEnd=2.4, clientStatus="passed")])
        with self.assertRaises(ValueError): Submission.model_validate(data)

    def test_extension_keeps_previous_evidence_and_marks_reproducibility(self):
        meta = dict(stopReason="completed", lowerBoundaryStatus="model_limit", upperBoundaryStatus="time_limit")
        parent = build_report(meta, [dict(targetMidi=60, status="passed", metrics=None)])
        report = build_report(meta, [dict(targetMidi=60, status="passed", metrics=None)], parent)
        self.assertEqual("reconfirmed", next(n for n in report["notes"] if n["midi"] == 60)["status"])

    def test_quality_retry_does_not_invalidate_a_verified_pass(self):
        meta = dict(stopReason="completed", lowerBoundaryStatus="model_limit", upperBoundaryStatus="time_limit")
        report = build_report(meta, [dict(targetMidi=60, status="insufficient_quality", metrics=None),
                                     dict(targetMidi=60, status="passed", metrics=None)])
        self.assertEqual("passed", next(n for n in report["notes"] if n["midi"] == 60)["status"])
        self.assertEqual([[60, 60]], report["stableIntervals"])

    def test_server_conflict_does_not_confirm_client_pass(self):
        import numpy as np
        from guided_range import analyze
        data = dict(sessionId="conflict", measurementType="guided_range", protocolVersion="guided-range-v1", startMidi=60,
                    noiseRms=.0001, durationSeconds=3.5, stopReason="user_stopped", lowerBoundaryStatus="not_probed",
                    upperBoundaryStatus="not_probed", attempts=[dict(attemptId="1", targetMidi=60, direction="anchor", cueStart=0,
                        cueEnd=.5, responseStart=.75, responseEnd=3.5, evaluationStart=1, evaluationEnd=3, clientStatus="passed")])
        frames = [dict(t=i/100, duration=.01, f0=440*2**((48-69)/12), clarity=1.0, rmsLow=.1, clippedRatio=0) for i in range(350)]
        with patch("guided_range.extract_frames", return_value=(frames, np.ones(350, bool), np.ones(350, bool), np.zeros(350, bool), {})):
            report = analyze(Path("unused.wav"), Submission.model_validate(data))
        self.assertEqual("needs_review", report["attemptResults"][0]["status"])
        self.assertEqual([], report["stableIntervals"])
        self.assertIsNone(report["highMidi"])

    def test_preview_rejects_remote_requests_and_invalid_metadata(self):
        from fastapi.testclient import TestClient
        from range_preview_app import app
        remote = TestClient(app, client=("192.0.2.1", 50000))
        response = remote.post("/preview/guided-range", files={"audio": ("a.wav", b"fake")}, data={"metadata": "{}"})
        self.assertEqual(403, response.status_code)
        local = TestClient(app, client=("127.0.0.1", 50000))
        response = local.post("/preview/guided-range", files={"audio": ("a.wav", b"fake")}, data={"metadata": "{}"})
        self.assertEqual(422, response.status_code)


if __name__ == "__main__": unittest.main()
