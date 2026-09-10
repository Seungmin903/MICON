import json
import math
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from range_models import Submission
from range_evaluator import build_report
from relative_hold import Tracker, passes, credit


def frames(midi=64.35, count=125, offset=0):
    return [dict(t=offset+i*.02, duration=.02, f0=440*2**((midi-69)/12), clarity=1,
                 rmsLow=.1, clippedRatio=0, agreement=True) for i in range(count)]


def track(fs, target=64, anchor=60):
    t=Tracker(target,anchor,.001)
    for f in fs: t.accept(f)
    return t


def metadata(seconds=4):
    return dict(sessionId='c480cfae-bf9a-482a-960f-4d3100e4a0e4',measurementType='guided_range',protocolVersion='guided-range-v4',
        mode='basic',startMidi=60,noiseRms=.0001,durationSeconds=seconds,stopReason='user_stopped',
        lowerBoundaryStatus='not_probed',upperBoundaryStatus='user_stopped',
        sampling=dict(basicCompleted=False,refinementStatus='not_started'),
        attempts=[dict(attemptId='a',visitId='v',targetMidi=64,direction='up',cueStart=0,cueEnd=.5,
            responseStart=.75,responseEnd=seconds,evaluationStart=1.2,evaluationEnd=3.7,clientStatus='passed',
            selectedForVisit=True,advanceReason='threshold',progressQualified=True,searchOutcome='reached')])


class RangeV4Tests(unittest.TestCase):
    def test_offset_rounding_and_one_semitone_limit(self):
        m=track(frames()).best['metrics']
        self.assertTrue(passes(m)); self.assertEqual(64,m['creditedMidi'])
        self.assertAlmostEqual(64.35,m['actualMidi']); self.assertAlmostEqual(100,m['stabilityScore'])
        self.assertEqual(63,track(frames(63.8)).best['metrics']['creditedMidi'])
        self.assertEqual(57,track(frames(56.2),56).best['metrics']['creditedMidi'])
        self.assertIsNone(track(frames(65.01)).best)
        self.assertIsNone(track(frames(76)).best)
        self.assertTrue(passes(track(frames(65)).best['metrics']))
        self.assertIsNone(credit(85,84,60))

    def test_breaks_preserve_short_longest_and_ties_keep_first(self):
        for kind in ('silence','missing','clip','drift'):
            fs=frames(count=40)
            gap=frames(count=1,offset=.8)[0]
            if kind=='silence': gap['clarity']=0
            if kind=='clip': gap['clippedRatio']=.02
            if kind=='drift': gap['f0']=440*2**((65-69)/12)
            if kind!='missing': fs.append(gap)
            fs+=frames(count=30,offset=.82)
            m=track(fs).best['metrics']
            self.assertAlmostEqual(.8,m['continuousHoldSeconds'],msg=kind)
            self.assertFalse(passes(m))
        t=track(frames(count=50)+[dict(frames(count=1,offset=1)[0],clarity=0)]+frames(count=50,offset=1.02))
        self.assertEqual(0,t.best['start'])

    def test_duration_and_steadiness_both_change_score(self):
        fs=frames()
        for i,f in enumerate(fs): f['f0']*=2**((.1 if i%2 else -.1)/12)
        m=track(fs).best['metrics']
        self.assertAlmostEqual(2.5,m['continuousHoldSeconds'])
        self.assertLess(m['stabilityScore'],100)
        self.assertLess(track(frames(count=1)).best['metrics']['stabilityScore'],2)
        self.assertFalse(passes(track(frames(count=49)).best['metrics']))
        self.assertTrue(passes(track(frames(count=50)).best['metrics']))

    def test_contract_accepts_short_manual_and_preserves_legacy_two_seconds(self):
        data=metadata(); Submission.model_validate(data)
        a=data['attempts'][0]; a.update(evaluationEnd=2,clientStatus='not_passed',advanceReason='manual',progressQualified=False,searchOutcome='manual_failed')
        Submission.model_validate(data)
        data['protocolVersion']='guided-range-v3'
        with self.assertRaises(ValueError): Submission.model_validate(data)
        data=metadata(); data['attempts'][0]['evaluationEnd']=2.2
        Submission.model_validate(data)
        data['attempts'][0]['evaluationEnd']=2.19
        with self.assertRaises(ValueError): Submission.model_validate(data)

    def test_map_fills_extrema_without_inventing_note_scores(self):
        m=track(frames()).best['metrics']
        results=[dict(attemptId='a',targetMidi=60,creditedMidi=60,status='passed',metrics=m,observedMidis=[58,60]),
                 dict(attemptId='b',targetMidi=68,creditedMidi=68,status='passed',metrics=m,observedMidis=[68,69])]
        r=build_report(metadata(),results)
        self.assertEqual(dict(overall=[58,69],stable=[60,68],recommended=[62,66]),r['rangeMap'])
        self.assertEqual([[60,68]],r['stableIntervals'])
        self.assertEqual('unmeasured',r['notes'][64-36]['status'])
        self.assertIsNone(r['notes'][64-36]['metrics'])
        narrow=build_report(metadata(),results[:1])
        self.assertIsNone(narrow['rangeMap']['recommended'])
        self.assertEqual('partial',narrow['availability'])
        data=metadata(); data['sampling']['basicCompleted']=True
        self.assertEqual('available',build_report(data,results)['availability'])

    def test_conflicts_and_uncertainty_cannot_set_stable_boundaries(self):
        m=track(frames()).best['metrics']
        r=build_report(metadata(),[dict(targetMidi=64,creditedMidi=64,status='needs_review',metrics=m,observedMidis=[64])])
        self.assertIsNone(r['rangeMap']['stable']); self.assertEqual([64,64],r['rangeMap']['overall'])
        self.assertEqual('partial',r['availability'])

    def fake_analyze(self,data,by_attempt):
        import numpy as np
        import soundfile as sf
        from guided_range_v4 import analyze
        def consume(recording, temporary, attempts, noise, on_frames):
            for a in attempts: on_frames(a,by_attempt[a.attemptId])
            return {}
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'test.wav'; sf.write(p,np.zeros(round(data['durationSeconds']*16000)),16000)
            with patch('guided_range_v4.response_register',side_effect=consume):
                return analyze(p,Submission.model_validate(data))

    def test_server_finds_longest_across_replay_and_brief_observations(self):
        data=metadata(8); a=data['attempts'][0]
        a.update(responseEnd=4,advanceReason='manual',evaluationEnd=2.4)
        b=dict(a,attemptId='b',cueStart=4,cueEnd=4.5,responseStart=4.75,responseEnd=8,
               evaluationStart=5.2,evaluationEnd=6.2,selectedForVisit=False)
        data['attempts'].append(b)
        r=self.fake_analyze(data,{'a':frames(count=80,offset=1), 'b':frames(63.8,count=140,offset=5)})
        selected=next(a for a in r['attemptResults'] if a['selectedForVisit'])
        self.assertEqual('b',selected['attemptId'])
        self.assertEqual(63,selected['creditedMidi'])
        self.assertAlmostEqual(2.5,selected['metrics']['continuousHoldSeconds'])
        self.assertTrue(selected['frames'])
        self.assertAlmostEqual(2.5,sum(f['duration'] for f in selected['frames']))
        self.assertTrue(all(selected['evaluationStart'] <= f['t'] < selected['evaluationEnd'] for f in selected['frames']))
        self.assertEqual([63,64],r['rangeMap']['overall'])

    def test_unagreed_model_pitch_is_uncertainty_not_zero_score(self):
        fs=[dict(f,agreement=False) for f in frames(count=145,offset=1)]
        r=self.fake_analyze(metadata(),{'a':fs})
        self.assertEqual('insufficient_quality',r['notes'][64-36]['status'])
        self.assertIsNone(r['notes'][64-36]['metrics'])

    @unittest.skipUnless(os.environ.get('RUN_RANGE_MODEL_TESTS')=='1','opt-in real CREPE/pYIN')
    def test_real_model_verifies_shifted_voice_and_v4_contract(self):
        import numpy as np
        import soundfile as sf
        from fastapi.testclient import TestClient
        import range_preview_app as preview
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'v4.wav'; t=np.arange(4*16000)/16000
            hz=440*2**((64.35-69)/12)
            x=(.25*np.sin(2*np.pi*hz*t)+.08*np.sin(4*np.pi*hz*t)+.04*np.sin(6*np.pi*hz*t))*(t>=1)
            sf.write(p,x,16000,subtype='PCM_16')
            with patch.object(preview,'STORAGE',Path(d)/'reports'),p.open('rb') as f:
                r=TestClient(preview.app,client=('127.0.0.1',50000)).post('/preview/guided-range',
                    files={'audio':('v4.wav',f,'audio/wav')},data={'metadata':json.dumps(metadata())})
            self.assertEqual(200,r.status_code,r.text[:1000])
            report=r.json(); note=report['notes'][64-36]
            self.assertEqual('passed',note['status'])
            self.assertGreater(note['metrics']['actualMidi'],64.1)
            self.assertGreater(note['metrics']['stabilityScore'],90)
            self.assertEqual([64,64],report['rangeMap']['stable'])
