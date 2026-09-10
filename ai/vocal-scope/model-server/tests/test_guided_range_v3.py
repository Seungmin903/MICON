import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from range_models import Submission
from range_evaluator import evaluate, build_report
from test_guided_range_v2 import submission


def v3():
    data = submission(protocolVersion="guided-range-v3", durationSeconds=4,
                      sampling=dict(basicCompleted=True, refinementStatus="not_started", measuredNoteCount=49,
                                    upperEnd=64, lowerEnd=56))
    data["attempts"][0].update(responseEnd=4, evaluationStart=1.4, evaluationEnd=3.4,
        progressEvaluationStart=1.4, progressEvaluationEnd=3.4, searchOutcome="reached")
    return data


def frames(seconds=4, fragmented=False):
    import math
    return [dict(t=i/100, duration=.01, f0=440*2**((60 + (2 if fragmented and i % 50 >= 40 else 0)-69)/12),
                 clarity=1, rmsLow=.1, clippedRatio=0) for i in range(seconds*100)]


class RangeV3Tests(unittest.TestCase):
    def test_continuous_hold_does_not_bridge_invalid_or_missing_frames(self):
        fs = frames(2)
        fs[99]["f0"] = 0
        m = evaluate(fs, 60, 0, 2, .001)
        self.assertAlmostEqual(1.99, m["longestHoldSeconds"])
        self.assertAlmostEqual(1, m["continuousHoldSeconds"])
        del fs[100]
        self.assertLess(evaluate(fs, 60, 0, 2, .001)["continuousHoldSeconds"], 1)
        m = evaluate(frames(2, True), 60, 0, 2, .001)
        self.assertAlmostEqual(.8, m["inTuneRatio"])
        self.assertAlmostEqual(.4, m["continuousHoldSeconds"])

    def test_clipped_frames_cannot_complete_a_continuous_second(self):
        fs = frames(2)
        fs[99]["clippedRatio"] = fs[100]["clippedRatio"] = .02
        m = evaluate(fs,60,0,2,.001)
        self.assertLess(m["clippedRatio"],.01)
        self.assertLess(m["continuousHoldSeconds"],1)

    def test_v3_requires_separate_exact_advancement_evidence(self):
        Submission.model_validate(v3())
        for updates in [dict(progressEvaluationStart=None, progressEvaluationEnd=None),
                        dict(progressEvaluationEnd=3.3), dict(searchOutcome=None),
                        dict(progressQualified=False), dict(progressEvaluationStart=.8, progressEvaluationEnd=2.8)]:
            data=v3(); data["attempts"][0].update(updates)
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                Submission.model_validate(data)
        data=v3(); data["protocolVersion"]="guided-range-v2"
        with self.assertRaises(ValueError): Submission.model_validate(data)

    def test_silent_manual_skip_is_sampling_failure_but_unmeasured(self):
        data=v3(); data["attempts"][0].update(cueEnd=.2,responseStart=.2,responseEnd=.2,
            evaluationStart=None,evaluationEnd=None,progressEvaluationStart=None,progressEvaluationEnd=None,
            clientStatus="unmeasured",advanceReason="manual",progressQualified=False,searchOutcome="manual_failed")
        meta=Submission.model_validate(data)
        report=build_report(meta.model_dump(),[dict(targetMidi=60,status="unmeasured",metrics=None,selectedForVisit=True)])
        self.assertEqual("unmeasured",report["notes"][24]["status"])
        self.assertEqual(0,report["sampling"]["measuredNoteCount"])
        self.assertEqual("insufficient_data",report["availability"])

    def analyze_fake(self,data,fs):
        import numpy as np
        import soundfile as sf
        from guided_range_v2 import analyze
        seconds=data["durationSeconds"]
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"audio.wav"; sf.write(path,np.zeros(round(16000*seconds)),16000)
            with patch("guided_range_v2.response_register",return_value={}), patch("guided_range_v2.decode_clip",
                return_value=(fs,np.ones(len(fs),bool),np.ones(len(fs),bool),np.zeros(len(fs),bool),{})) as decoder:
                result=analyze(path,Submission.model_validate(data))
                return result,decoder.call_count

    def test_model_verifies_best_and_advancement_from_different_replay_segments(self):
        data=v3(); data["durationSeconds"]=8
        first=data["attempts"][0]
        first.update(clientStatus="not_passed",progressEvaluationStart=None,progressEvaluationEnd=None)
        second=dict(first,attemptId="replay",selectedForVisit=False,cueStart=4,cueEnd=4.5,responseStart=4.75,
                    responseEnd=8,evaluationStart=5.4,evaluationEnd=7.4,progressEvaluationStart=5.4,progressEvaluationEnd=7.4)
        data["attempts"].append(second)
        fs=frames(8,True)
        exact=frames(8)
        for i in range(400,800):
            fs[i]["f0"]=exact[i]["f0"] if 540 <= i < 640 else exact[i]["f0"]*2**(2/12)
        report,calls=self.analyze_fake(data,fs)
        result=report["attemptResults"][0]
        self.assertEqual(2,calls)
        self.assertEqual("not_passed",result["status"])
        self.assertAlmostEqual(.8,result["metrics"]["inTuneRatio"])
        self.assertAlmostEqual(.5,result["progressMetrics"]["inTuneRatio"])
        self.assertTrue(result["serverProgressQualified"])
        self.assertEqual("replay",result["progressEvidenceAttemptId"])
        self.assertEqual("partial",report["availability"])
        self.assertEqual(1,report["sampling"]["measuredNoteCount"])

    def test_fragmented_server_progress_cannot_confirm_a_claimed_move(self):
        data=v3(); data["attempts"][0]["clientStatus"]="not_passed"
        report,_=self.analyze_fake(data,frames(4,True))
        self.assertEqual("needs_review",report["notes"][24]["status"])
        self.assertIsNone(report["lowMidi"])
        self.assertEqual([],report["stableIntervals"])
        self.assertFalse(report["attemptResults"][0]["serverProgressQualified"])

    def test_v3_conflict_is_excluded_even_with_earlier_stable_success(self):
        data=v3()
        result=build_report(data,[dict(targetMidi=60,status="passed",metrics=dict(inTuneRatio=1),selectedForVisit=True),
                                  dict(targetMidi=60,status="needs_review",metrics=None,selectedForVisit=True)])
        self.assertIsNone(result["lowMidi"])
        self.assertEqual([],result["stableIntervals"])

    @unittest.skipUnless(os.environ.get("RUN_RANGE_MODEL_TESTS")=="1","opt-in real models")
    def test_real_model_v3_upload_and_exact_continuous_hold(self):
        import numpy as np
        import soundfile as sf
        from fastapi.testclient import TestClient
        import range_preview_app as preview
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"v3.wav"
            t=np.arange(4*16000)/16000
            hz=440*2**((60-69)/12)
            audio=(.25*np.sin(2*np.pi*hz*t)+.08*np.sin(4*np.pi*hz*t)+.04*np.sin(6*np.pi*hz*t))*(t>=1)
            sf.write(path,audio,16000,subtype="PCM_16")
            with patch.object(preview,"STORAGE",Path(d)/"reports"),path.open("rb") as f:
                response=TestClient(preview.app,client=("127.0.0.1",50000)).post("/preview/guided-range",
                    files={"audio":("v3.wav",f,"audio/wav")},data={"metadata":json.dumps(v3())})
            self.assertEqual(200,response.status_code,response.text[:1000])
            report=response.json()
            self.assertEqual("passed",report["notes"][24]["status"])
            self.assertTrue(report["attemptResults"][0]["serverProgressQualified"])
            self.assertEqual(1,report["sampling"]["measuredNoteCount"])
