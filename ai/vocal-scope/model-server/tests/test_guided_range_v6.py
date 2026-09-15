import copy
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_guided_range_v5 import metadata, frames, ROOT
from range_models import Submission
from guided_range import analyze
from guided_range_v5 import verify
from range_evaluator import build_report


def adaptive_metadata(segments=None):
    data, fs = metadata(segments)
    data.update(protocolVersion='guided-range-v6', sampling=dict(strategy='adaptive_8', coarseStep=8))
    data['attempts'][0].update(probeStage='anchor', anchorMidi=60, advanceReason='user_stopped')
    if not data['attempts'][0]['progressQualified']:
        data['attempts'][0]['searchOutcome'] = None
    return data, fs


class RangeV6Tests(unittest.TestCase):
    def test_sampling_contract_and_anchor_reset_are_versioned(self):
        data, _ = adaptive_metadata()
        Submission.model_validate(data)
        for mutate in (
            lambda d: d.pop('sampling'),
            lambda d: d['sampling'].update(coarseStep=2),
            lambda d: d['attempts'][0].pop('anchorMidi'),
            lambda d: d['attempts'][0].update(probeStage='bisect'),
            lambda d: d['sampling'].update(upperFirstFailure=68, upperBoundaryPair=[68,60]),
            lambda d: d.update(upperBoundaryStatus='boundary_refined'),
        ):
            bad=copy.deepcopy(data);mutate(bad)
            with self.assertRaises(ValueError): Submission.model_validate(bad)
        data['attempts'][0].update(advanceReason='anchor_reset',runEndReason='anchor_reset',progressQualified=False,searchOutcome=None)
        data['startMidi']=None
        self.assertEqual(60,Submission.model_validate(data).attempts[0].anchorMidi)
        data['protocolVersion']='guided-range-v5'
        with self.assertRaises(ValueError): Submission.model_validate(data)

    def test_v6_reuses_exact_v5_acoustic_metrics_and_keeps_sparse_verified_notes(self):
        data,fs=adaptive_metadata()
        a=Submission.model_validate(data).attempts[0]
        result=verify(a,fs,60,.001)
        self.assertEqual('passed',result['status'])
        self.assertEqual('anchor',result['probeStage'])
        self.assertAlmostEqual(.3,result['metrics']['bufferSeconds'])
        other=dict(result,targetMidi=68,creditedMidi=68,metrics=dict(result['metrics'],creditedMidi=68))
        report=build_report(data,[result,other])
        self.assertEqual([[60,60],[68,68]],report['stableIntervals'])
        self.assertIsNone(report['rangeMap'])
        self.assertTrue(all(report['notes'][m-36]['metrics'] is None for m in range(61,68)))
        self.assertEqual('partial',report['availability'])
        reviewed=build_report(data,[result,dict(other,status='needs_review')])
        self.assertIsNone(reviewed['notes'][68-36]['metrics'])
        self.assertEqual(60,reviewed['highMidi'])
        with self.assertRaises(ValueError): build_report(data,[result],dict(report,protocolVersion='guided-range-v5'))

    def test_recorded_anchor_before_reset_is_used_during_model_analysis(self):
        import numpy as np
        import soundfile as sf
        data,_=adaptive_metadata()
        data['startMidi']=64
        data['attempts'][0].update(advanceReason='anchor_reset',runEndReason='anchor_reset',progressQualified=False,searchOutcome=None)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'bounded.wav'
            sf.write(path,np.zeros(round(data['durationSeconds']*16000)),16000)
            with patch('guided_range_v5.decode_clip',return_value=([],None,None)), \
                 patch('guided_range_v5.response_register',return_value={}), \
                 patch('guided_range_v5.verify',return_value=dict(targetMidi=60,status='unmeasured',metrics=None)) as checked:
                analyze(path,Submission.model_validate(data))
            self.assertEqual(60,checked.call_args.args[2])

    def test_android_exported_sessions_validate_without_losing_sampling(self):
        directory=ROOT.parents[2]/'frontend/android/app/build/range-v6-contract'
        if not directory.exists(): self.skipTest('run Android unit tests to produce cross-language contracts')
        for name in ('basic','extended','anchor-reset','recovered'):
            with self.subTest(name=name):
                data=json.loads((directory/(name+'.json')).read_text(encoding='utf-8'))
                parsed=Submission.model_validate(data)
                self.assertEqual('adaptive_8',parsed.sampling.strategy)
                self.assertTrue(all(a.anchorMidi is not None for a in parsed.attempts))
                if name in ('basic','extended'):
                    self.assertEqual([65,66],parsed.sampling.upperBoundaryPair)
                    self.assertEqual([55,54],parsed.sampling.lowerBoundaryPair)

    @unittest.skipUnless(os.environ.get('RUN_RANGE_MODEL_TESTS')=='1','actual CREPE/pYIN opt-in')
    def test_actual_model_accepts_v6_with_v5_evaluator(self):
        import numpy as np
        import soundfile as sf
        data,_=adaptive_metadata([(1.2,0)])
        rate=16000;t=np.arange(round(data['durationSeconds']*rate))/rate
        hz=440*2**((60-69)/12)
        x=(.25*np.sin(2*np.pi*hz*t)+.08*np.sin(4*np.pi*hz*t)+.04*np.sin(6*np.pi*hz*t))*(t>=.95)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'actual.wav';sf.write(path,x,rate,subtype='PCM_16')
            report=analyze(path,Submission.model_validate(data))
        self.assertEqual('guided-range-v6',report['protocolVersion'])
        self.assertEqual('passed',report['notes'][24]['status'],report['attemptResults'])
        self.assertEqual('adaptive_8',report['sampling']['strategy'])
        self.assertEqual([[60,60]],report['stableIntervals'])
