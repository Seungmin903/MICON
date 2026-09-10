import copy
import json
import math
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from buffered_hold import evaluate, passes
from guided_range_v5 import verify, aligned_levels, device_clock
from range_evaluator import build_report
from range_models import Submission


def frames(segments, offset=1.0, midi=60):
    result=[]; t=offset
    for seconds,cents in segments:
        end=t+seconds
        while t < end-1e-8:
            dt=min(.01,end-t); m=midi+cents/100
            result.append(dict(t=t,duration=dt,f0=440*2**((m-69)/12),clarity=1.,rmsLow=.1,
                clippedRatio=0.,rawMidi=m,pyinMidi=m,pyinVoiced=True,baseVoiced=True,voiced=True,periodicity=.99))
            t+=dt
    return result


def metadata(segments=None):
    segments=segments or [(1.,0),(.3,70),(.2,0)]
    fs=frames([(.2,0)]+segments)
    end=fs[-1]['t']+fs[-1]['duration']
    m,_=evaluate(fs,60,60,1,1.2,1.2,end,.001)
    passed=passes(m)
    return dict(sessionId='v5-test',measurementType='guided_range',protocolVersion='guided-range-v5',
        startMidi=60,noiseRms=.0001,durationSeconds=end+.3,stopReason='user_stopped',
        lowerBoundaryStatus='not_probed',upperBoundaryStatus='not_probed',
        sampling=dict(basicCompleted=True),attempts=[dict(attemptId='a',visitId='v',targetMidi=60,direction='anchor',
            cueStart=0,cueEnd=.5,responseStart=.75,responseEnd=end+.3,
            evaluationStart=1.2,evaluationEnd=end,referenceStart=1.,referenceEnd=1.2,referenceMidi=60,
            runEndReason='manual',clientStatus='passed' if passed else 'not_passed',metrics=m,
            advanceReason='manual',progressQualified=passed,searchOutcome='reached' if passed else 'manual_failed')]),fs


class RangeV5Tests(unittest.TestCase):
    def test_shared_fixtures(self):
        for c in json.loads((ROOT.parents[2]/'tests/fixtures/guided-range-v5.json').read_text()):
            with self.subTest(case=c['name']):
                data,fs=metadata(c['segments']);a=data['attempts'][0]
                m,q=evaluate(fs,60,60,1,1.2,1.2,a['evaluationEnd'],.001)
                for key,field in [('T','creditedHoldSeconds'),('H','continuousHoldSeconds'),('B','bufferSeconds'),
                                  ('O','outsideSeconds'),('penalty','excursionPenalty')]:
                    self.assertAlmostEqual(c[key],m[field])
                self.assertEqual(c['passed'],passes(m));self.assertFalse(q['inputUncertain'])
                self.assertIsNone(q['prematureEnd'])

    def test_exact_reference_uses_median_and_requires_valid_duration(self):
        data,fs=metadata();fs[0]['f0']=440*2**((60.9-69)/12)
        a=Submission.model_validate(data).attempts[0]
        r=verify(a,fs,60,.001);self.assertAlmostEqual(60,r['serverReferenceMidi'])
        for f in fs[:6]: f['pyinVoiced']=False
        self.assertEqual('insufficient_quality',verify(a,fs,60,.001)['status'])

    def test_uncertainty_clipping_missing_are_not_buffer_or_scored(self):
        for kind in ('confidence','volume','clip','missing'):
            data,fs=metadata();a=Submission.model_validate(data).attempts[0]
            if kind=='confidence':fs[50]['periodicity']=.61
            if kind=='volume':fs[50]['rmsLow']=.0009
            if kind=='clip':fs[50]['clippedRatio']=.02
            if kind=='missing':fs.pop(50)
            r=verify(a,fs,60,.001)
            self.assertEqual('insufficient_quality',r['status'],kind);self.assertIsNone(r['metrics'])

    def test_server_cannot_choose_a_better_run_or_drop_penalty_tail(self):
        data,fs=metadata([(1.,0),(.5,70)]);a=Submission.model_validate(data).attempts[0]
        r=verify(a,fs+frames([(2.5,0)],offset=5),60,.001)
        self.assertEqual(a.evaluationEnd,r['evaluationEnd'])
        self.assertAlmostEqual(.5,r['metrics']['bufferSeconds'])
        self.assertAlmostEqual(20,r['metrics']['excursionPenalty'])
        self.assertLess(r['metrics']['stabilityScore'],70)

    def test_pass_and_attribution_conflicts_remain_review(self):
        data,fs=metadata([(1.2,0)]);a=Submission.model_validate(data).attempts[0]
        shifted=frames([(.2,60),(1.2,60)])
        self.assertEqual('needs_review',verify(a,shifted,60,.001)['status'])
        data,fs=metadata([(.8,0)]);a=Submission.model_validate(data).attempts[0]
        self.assertEqual('not_passed',verify(a,fs,60,.001)['status'])

    def test_v5_contract_and_legacy_duration_rules(self):
        data,_=metadata();Submission.model_validate(data)
        for field in ('referenceStart','referenceEnd','referenceMidi','runEndReason'):
            bad=copy.deepcopy(data);bad['attempts'][0][field]=None
            with self.assertRaises(ValueError):Submission.model_validate(bad)
        bad=copy.deepcopy(data);bad['attempts'][0]['metrics']['excursionPenalty']=0
        with self.assertRaises(ValueError):Submission.model_validate(bad)
        data['protocolVersion']='guided-range-v3'
        with self.assertRaises(ValueError):Submission.model_validate(data)

    def test_sparse_notes_and_review_do_not_fill_intervals_or_scores(self):
        data,fs=metadata();a=Submission.model_validate(data).attempts[0];r=verify(a,fs,60,.001)
        other=dict(r,targetMidi=64,creditedMidi=64,metrics=dict(r['metrics'],creditedMidi=64))
        report=build_report(data,[r,other])
        self.assertEqual([[60,60],[64,64]],report['stableIntervals'])
        self.assertIsNone(report['rangeMap']);self.assertIsNone(report['notes'][61-36]['metrics'])
        review=dict(other,status='needs_review')
        report=build_report(data,[r,review]);self.assertIsNone(report['notes'][64-36]['metrics'])
        self.assertEqual([[60,60]],report['stableIntervals'])
        with self.assertRaises(ValueError):build_report(data,[r],dict(report,protocolVersion='guided-range-v4'))

    def test_aligned_rms_uses_original_centered_min_half_window(self):
        import numpy as np
        import soundfile as sf
        for rate in (16000,44100,48000):
            dec=3 if rate>=32000 else 1
            raw=(np.arange(rate)%701-350).astype(np.float32)/32768
            with tempfile.TemporaryDirectory() as d:
                p=Path(d)/'rms.wav';sf.write(p,raw,rate,subtype='PCM_16')
                with sf.SoundFile(p) as recording:
                    f=[dict(t=.5)];aligned_levels(recording,f)
                center=round(.5*rate/dec)*dec
                w=raw[center-512*dec:center+512*dec].reshape(-1,dec).mean(axis=1,dtype=np.float32).astype(np.float64)
                self.assertAlmostEqual(min(np.sqrt(np.mean(w[:512]**2)),np.sqrt(np.mean(w[512:]**2))),f[0]['rmsLow'],places=10)

    def test_device_clock_keeps_alignment_and_missing_model_frames(self):
        source=frames([(1.,0)],offset=1.)
        aligned=device_clock(source,1.032,1.832,16000)
        self.assertEqual(40,len(aligned));self.assertAlmostEqual(1.032,aligned[0]['t'])
        self.assertTrue(all(abs(f['modelTime']-f['t']) <= .005001 for f in aligned))
        missing=device_clock([],1.032,1.052,16000)
        self.assertEqual(0.,missing[0]['f0']);self.assertFalse(missing[0]['voiced'])

    @unittest.skipUnless(os.environ.get('RUN_RANGE_MODEL_TESTS')=='1','opt-in real model')
    def test_real_model_selected_buffered_interval(self):
        import numpy as np
        import soundfile as sf
        from guided_range_v5 import analyze
        data,_=metadata([(1.2,0),(.2,70),(.3,0)])
        data['sessionId']='v5-real-isolated'
        rate=16000;t=np.arange(round(data['durationSeconds']*rate))/rate
        midi=np.where((t>=2.4)&(t<2.6),60.7,60.)
        hz=440*2**((midi-69)/12);phase=2*np.pi*np.cumsum(hz)/rate
        x=(.25*np.sin(phase)+.08*np.sin(2*phase)+.04*np.sin(3*phase))*(t>=.95)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'real.wav';sf.write(p,x,rate,subtype='PCM_16')
            r=analyze(p,Submission.model_validate(data))
        n=r['notes'][60-36]
        self.assertEqual('passed',n['status'],r['attemptResults'])
        self.assertGreater(n['metrics']['bufferSeconds'],.1)
        self.assertGreater(n['metrics']['excursionPenalty'],4)
        self.assertLess(n['metrics']['stabilityScore'],80)
