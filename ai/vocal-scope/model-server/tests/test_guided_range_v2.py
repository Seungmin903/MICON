import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from range_models import Submission
from range_evaluator import build_report, evaluate


def submission(**changes):
    a = dict(attemptId="one", visitId="visit-one", targetMidi=60, direction="anchor",
             cueStart=0, cueEnd=.5, responseStart=.75, responseEnd=610,
             evaluationStart=607.4, evaluationEnd=609.4, clientStatus="passed",
             advanceReason="threshold", progressQualified=True, selectedForVisit=True)
    data = dict(sessionId=str(uuid4()), measurementType="guided_range", protocolVersion="guided-range-v2",
                startMidi=60, noiseRms=.0001, durationSeconds=610, stopReason="user_stopped",
                lowerBoundaryStatus="not_probed", upperBoundaryStatus="user_stopped", attempts=[a])
    data.update(changes)
    return data


class RangeV2Tests(unittest.TestCase):
    def test_streamed_register_spectra_match_existing_frames_at_file_edges(self):
        import numpy as np
        import soundfile as sf
        from register_engine import _frame_spectra
        rate = 16000
        audio = np.random.default_rng(42).normal(0, .1, 3200).astype(np.float32)
        frames = np.array([0, 1, 7, 10, 19, 20])
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "register.wav"
            sf.write(path, audio, rate, subtype="FLOAT")
            np.testing.assert_allclose(_frame_spectra(audio, rate, frames, 2048),
                                       _frame_spectra(path, rate, frames, 2048), atol=1e-5)

    def test_long_v2_and_legacy_time_validation(self):
        data = submission()
        self.assertEqual(610, Submission.model_validate(data).durationSeconds)
        data["protocolVersion"] = "guided-range-v1"
        with self.assertRaises(ValueError):
            Submission.model_validate(data)

    def test_representative_and_window_are_required(self):
        for field, value in (("selectedForVisit", False), ("visitId", None), ("evaluationStart", None)):
            data = submission()
            data["attempts"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                Submission.model_validate(data)

    def test_skip_during_cue_has_no_score_or_failure(self):
        data = submission(durationSeconds=.2)
        data["attempts"][0].update(cueEnd=.2, responseStart=.2, responseEnd=.2,
            evaluationStart=None, evaluationEnd=None, clientStatus="unmeasured", advanceReason="manual", progressQualified=False)
        s = Submission.model_validate(data)
        report = build_report(s.model_dump(), [dict(targetMidi=60, status="unmeasured", selectedForVisit=True, metrics=None)])
        self.assertEqual("unmeasured", report["notes"][24]["status"])
        self.assertIsNone(report["notes"][24]["metrics"])

    def test_best_visit_score_does_not_hide_reproducibility_or_fill_gaps(self):
        def metric(p):
            return dict(inTuneRatio=p)
        results = [
            dict(attemptId="a", targetMidi=60, status="not_passed", metrics=metric(.4), selectedForVisit=True),
            dict(attemptId="b", targetMidi=60, status="not_passed", metrics=metric(.2), selectedForVisit=False),
            dict(attemptId="c", targetMidi=62, status="passed", metrics=metric(.96), selectedForVisit=True),
            dict(attemptId="d", targetMidi=62, status="not_passed", metrics=metric(.3), selectedForVisit=True),
        ]
        report = build_report(submission(), results)
        self.assertEqual(.4, report["notes"][24]["metrics"]["inTuneRatio"])
        self.assertEqual(1, report["notes"][24]["attempts"])
        self.assertEqual("unmeasured", report["notes"][25]["status"])
        self.assertEqual("inconsistent", report["notes"][26]["status"])
        self.assertEqual(.96, report["notes"][26]["metrics"]["inTuneRatio"])
        self.assertEqual([], report["stableIntervals"])

    def test_graph_without_stable_pass_is_partial_and_mixed_versions_rejected(self):
        results = [dict(targetMidi=60, status="not_passed", metrics=dict(inTuneRatio=.55), selectedForVisit=True)]
        report = build_report(submission(), results)
        self.assertEqual("partial", report["availability"])
        self.assertIsNone(report["lowMidi"])
        report["protocolVersion"] = "guided-range-v1"
        with self.assertRaises(ValueError):
            build_report(submission(), results, report)

    def test_model_conflict_does_not_confirm_selected_window(self):
        import numpy as np
        import soundfile as sf
        from guided_range_v2 import analyze
        meta = submission(durationSeconds=4)
        meta["attempts"][0].update(responseEnd=4, evaluationStart=1.4, evaluationEnd=3.4)
        # Exact server window is an octave below the client's claimed stable pass.
        frames = [dict(t=i / 100, duration=.01, f0=130.8128, clarity=1, rmsLow=.1, clippedRatio=0) for i in range(400)]
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "audio.wav"
            sf.write(path, np.zeros(64000), 16000)
            with patch("guided_range_v2.response_register", return_value={}), patch("guided_range_v2.decode_clip",
                    return_value=(frames, np.ones(400, bool), np.ones(400, bool), np.zeros(400, bool), {})):
                report = analyze(path, Submission.model_validate(meta))
        self.assertEqual("needs_review", report["notes"][24]["status"])
        self.assertEqual([], report["stableIntervals"])


@unittest.skipUnless(os.environ.get("RUN_RANGE_MODEL_TESTS") == "1", "opt-in real CREPE/pYIN test")
class LongModelTests(unittest.TestCase):
    def test_ten_minute_upload_uses_bounded_reads_and_real_models(self):
        import numpy as np
        import soundfile as sf
        from fastapi.testclient import TestClient
        import range_preview_app as preview
        import guided_range_v2 as v2
        import ctypes
        from ctypes import wintypes
        rate = 16000
        class Memory(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [(name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
        def memory():
            if os.name != "nt":
                import resource
                return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
            counters = Memory()
            counters.cb = ctypes.sizeof(counters)
            ctypes.windll.kernel32.GetCurrentProcess.restype = ctypes.c_void_p
            handle = ctypes.c_void_p(ctypes.windll.kernel32.GetCurrentProcess())
            if not ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                raise OSError("Cannot inspect model memory")
            return counters.PeakWorkingSetSize
        before = memory()
        largest = 0
        original = v2.decode_clip
        def bounded(recording, temporary, attempt, start, end, noise):
            nonlocal largest
            largest = max(largest, end - start)
            self.assertLessEqual(end - start, 30.001)
            return original(recording, temporary, attempt, start, end, noise)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "ten-minutes.wav"
            with sf.SoundFile(path, "w", samplerate=rate, channels=1, subtype="PCM_16") as f:
                for second in range(610):
                    t = np.arange(rate) / rate
                    hz = 440 * 2 ** ((60 - 69) / 12)
                    block = .25*np.sin(2*np.pi*hz*t)+.08*np.sin(4*np.pi*hz*t)+.04*np.sin(6*np.pi*hz*t) if second >= 607 else np.zeros(rate)
                    f.write(block)
            self.assertGreater(path.stat().st_size, 10_000_000)
            with path.open("rb") as audio, patch.object(preview, "STORAGE", Path(d) / "reports"), patch.object(v2, "decode_clip", side_effect=bounded):
                response = TestClient(preview.app, client=("127.0.0.1", 50000)).post("/preview/guided-range",
                    files={"audio": ("ten-minutes.wav", audio, "audio/wav")}, data={"metadata": json.dumps(submission())})
            self.assertEqual(200, response.status_code, response.text[:1000])
            report = response.json()
            self.assertEqual("passed", report["notes"][24]["status"], report["attemptResults"][0].get("quality"))
            self.assertAlmostEqual(607.4, report["attemptResults"][0]["evaluationStart"])
            self.assertLessEqual(len(report["attemptResults"][0]["frames"]), 201)
            print(json.dumps(dict(audioBytes=path.stat().st_size, durationSeconds=610, largestDecodeSeconds=largest,
                                  peakWorkingSetBeforeMB=round(before/1024**2, 1), peakWorkingSetAfterMB=round(memory()/1024**2, 1))))
