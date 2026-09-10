import copy
import json
import os
import tempfile
import unittest
from pathlib import Path

from test_guided_range_v5 import ROOT, frames
from test_guided_range_v6 import adaptive_metadata
from buffered_hold import evaluate, passes
from guided_range_v5 import verify
from guided_range import analyze
from range_models import Submission
from range_evaluator import build_report


def revised_metadata(segments=None):
    data, fs = adaptive_metadata(segments)
    data['protocolVersion'] = 'guided-range-v7'
    a = data['attempts'][0]
    a['metrics'], _ = evaluate(fs, 60, 60, a['referenceStart'], a['referenceEnd'],
        a['evaluationStart'], a['evaluationEnd'], .001, revised=True)
    return data, fs


class RangeV7Tests(unittest.TestCase):
    def test_short_perfect_hold_scores_40_and_legacy_stays_70(self):
        data, fs = revised_metadata([(1., 0)])
        a = Submission.model_validate(data).attempts[0]
        revised = verify(a, copy.deepcopy(fs), 60, .001, revised=True)
        legacy = verify(a, copy.deepcopy(fs), 60, .001)
        self.assertAlmostEqual(40, revised['metrics']['stabilityScore'])
        self.assertAlmostEqual(70, legacy['metrics']['stabilityScore'])
        self.assertAlmostEqual(0, revised['metrics']['referenceRmsCents'])
        self.assertNotIn('referenceRmsCents', legacy['metrics'])
        self.assertEqual('passed', revised['status'])

    def test_fixed_center_and_completed_penalty_tail(self):
        data, fs = revised_metadata([(1.2,30),(.3,100)])
        a = Submission.model_validate(data).attempts[0]
        result = verify(a, fs, 60, .001, revised=True)
        m = result['metrics']
        self.assertAlmostEqual(12, m['stabilityScore'])
        self.assertAlmostEqual(12, m['excursionPenalty'])
        self.assertGreater(m['referenceRmsCents'], m['pitchSpreadCents'])
        self.assertTrue(passes(m))

    def test_version_contract_rejects_missing_nonfinite_evidence_and_cross_version_merge(self):
        data, fs = revised_metadata()
        for value in (None, -1, float('nan'), float('inf')):
            bad = copy.deepcopy(data); bad['attempts'][0]['metrics']['referenceRmsCents'] = value
            with self.assertRaises(ValueError): Submission.model_validate(bad)
        a = Submission.model_validate(data).attempts[0]
        r = verify(a, fs, 60, .001, revised=True)
        report = build_report(data, [r])
        with self.assertRaises(ValueError): build_report(data, [r], dict(report, protocolVersion='guided-range-v6'))
        reviewed = build_report(data, [dict(r, status='needs_review')])
        self.assertIsNone(reviewed['notes'][24]['metrics'])

    def test_android_v7_completed_window_contract_matches_server(self):
        path = ROOT.parents[2]/'frontend/android/app/build/range-v7-contract/ended.json'
        if not path.exists(): self.skipTest('Android export not built yet')
        data = json.loads(path.read_text(encoding='utf-8'))
        parsed = Submission.model_validate(data)
        a = parsed.attempts[0]
        self.assertEqual('voice_ended', a.runEndReason)
        self.assertEqual('guided-range-v7', parsed.protocolVersion)
        self.assertEqual('adaptive_6', parsed.sampling.strategy)
        self.assertEqual(6, parsed.sampling.coarseStep)
        fs = frames([(.2,0),(1.2,0)], offset=a.referenceStart)
        result = verify(a, fs, 60, .001, revised=True)
        self.assertEqual('passed', result['status'])
        self.assertAlmostEqual(data['attempts'][0]['metrics']['stabilityScore'], result['metrics']['stabilityScore'])

    def test_six_semitone_metadata_and_existing_eight_semitone_jobs(self):
        data, _ = revised_metadata()
        self.assertEqual(8, Submission.model_validate(data).sampling.coarseStep)
        data['sampling'].update(strategy='adaptive_6',coarseStep=6,upperFirstFailure=66,upperBoundaryPair=[60,66])
        self.assertEqual(6, Submission.model_validate(data).sampling.coarseStep)
        for mutate in (
            lambda d: d['sampling'].update(coarseStep=8),
            lambda d: d.update(protocolVersion='guided-range-v6'),
            lambda d: d['sampling'].update(upperFirstFailure=68,upperBoundaryPair=[60,68]),
        ):
            bad=copy.deepcopy(data);mutate(bad)
            with self.assertRaises(ValueError): Submission.model_validate(bad)

    @unittest.skipUnless(os.environ.get('RANGE_RECENT_REPORT'), 'optional retained real-session evidence')
    def test_recent_e3_same_server_frames_score_22_point_5_and_other_notes_remain_high(self):
        report = json.loads(Path(os.environ['RANGE_RECENT_REPORT']).read_text(encoding='utf-8'))
        floor = max(.001, report['calibration']['noiseRms'] * 3.16)
        for target, low, high in ((52,20,30),(64,85,100),(72,85,100)):
            a = next(a for a in report['attemptResults'] if a['targetMidi']==target and a['selectedForVisit'] and a['status']=='passed')
            args = (a['frames'], target, a['anchorMidi'], a['referenceStart'], a['referenceEnd'], a['evaluationStart'], a['evaluationEnd'], floor)
            old, _ = evaluate(*args)
            new, _ = evaluate(*args, revised=True)
            self.assertAlmostEqual(a['metrics']['stabilityScore'], old['stabilityScore'])
            self.assertTrue(low <= new['stabilityScore'] <= high, (target,new))
            if target==52:
                self.assertAlmostEqual(22.534126345854226, new['stabilityScore'])
                self.assertAlmostEqual(50.74381109281854, old['stabilityScore'])

    @unittest.skipUnless(os.environ.get('RUN_RANGE_MODEL_TESTS')=='1','actual CREPE/pYIN opt-in')
    def test_actual_model_dispatches_v7_scoring(self):
        import numpy as np
        import soundfile as sf
        data, _ = revised_metadata([(1.2,0)])
        rate=16000; t=np.arange(round(data['durationSeconds']*rate))/rate
        hz=440*2**((60-69)/12)
        x=(.25*np.sin(2*np.pi*hz*t)+.08*np.sin(4*np.pi*hz*t)+.04*np.sin(6*np.pi*hz*t))*(t>=.95)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'actual.wav'; sf.write(path,x,rate,subtype='PCM_16')
            report=analyze(path,Submission.model_validate(data))
        self.assertEqual('guided-range-v7', report['protocolVersion'])
        note=report['notes'][24]
        self.assertEqual('passed', note['status'], report['attemptResults'])
        self.assertTrue(40 <= note['metrics']['stabilityScore'] <= 48.00001)
        self.assertIsNotNone(note['metrics']['referenceRmsCents'])
