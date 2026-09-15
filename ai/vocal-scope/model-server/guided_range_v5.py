"""Independent CREPE/pYIN verification of the same v5 reference and evaluation windows."""
import math
import tempfile
from pathlib import Path
import numpy as np
import soundfile as sf
from buffered_hold import evaluate, passes
from guided_range_v2 import decode_clip, response_register
from range_evaluator import build_report


def device_clock(frames, reference_start, evaluation_end, rate):
    """Observe model estimates on the device frame clock without filling missing pitch.

    Nearest CREPE output is at most 5 ms away (its native hop is 10 ms). RMS is
    recomputed on the original PCM at the exact device centers, including 44.1 kHz.
    """
    hop = 320 / (rate / (3 if rate >= 32000 else 1))
    result = []; i = 0; n = 0
    while reference_start + n*hop < evaluation_end - 1e-8:
        t = reference_start + n*hop
        while i+1 < len(frames) and abs(frames[i+1]['t']-t) < abs(frames[i]['t']-t):
            i += 1
        if frames and abs(frames[i]['t']-t) <= .005001:
            f = dict(frames[i], modelTime=frames[i]['t'], t=t, duration=hop)
        else:
            f = dict(t=t,duration=hop,f0=0.,clarity=0.,rmsLow=0.,clippedRatio=0.,
                     baseVoiced=False,voiced=False,periodicity=0.,agreement=False)
        result.append(f); n+=1
    return result


def aligned_levels(recording, frames):
    """1024 decimated samples, centered on each timestamp, same min-half RMS as Android.

    Read one bounded decoder block and its window margin. Float32 averaging matches
    capture preprocessing; clipping is measured before decimation on original PCM.
    """
    if not frames:
        return
    rate = recording.samplerate
    decimate = 3 if rate >= 32000 else 1
    analysis_rate = rate / decimate
    lo = max(0, math.floor((frames[0]['t'] * analysis_rate - 512)) * decimate)
    hi = min(len(recording), math.ceil((frames[-1]['t'] * analysis_rate + 512)) * decimate)
    recording.seek(lo)
    samples = recording.read(hi-lo, dtype='float32')
    for f in frames:
        center = round(f['t'] * analysis_rate) * decimate
        a, b = center - 512*decimate - lo, center + 512*decimate - lo
        if a < 0 or b > len(samples):
            f.update(rmsLow=0.0, rms=0.0, clippedRatio=0.0, inputWindowComplete=False)
            continue
        original = samples[a:b]
        window = original.reshape(-1, decimate).mean(axis=1, dtype=np.float32)
        square = window.astype(np.float64)**2
        f.update(rms=float(np.sqrt(square.mean())), rmsLow=float(min(np.sqrt(square[:512].mean()), np.sqrt(square[512:].mean()))),
                 clippedRatio=float(np.mean(np.abs(original) >= 32441/32768)), inputWindowComplete=True)


def verify(a, frames, anchor, floor, *, revised=False):
    result = dict(attemptId=a.attemptId, visitId=a.visitId, targetMidi=a.targetMidi,
        advanceReason=a.advanceReason, progressQualified=a.progressQualified, searchOutcome=a.searchOutcome,
        selectedForVisit=a.selectedForVisit, status='unmeasured', metrics=None, creditedMidi=None,
        referenceStart=a.referenceStart, referenceEnd=a.referenceEnd, referenceMidi=a.referenceMidi,
        evaluationStart=a.evaluationStart, evaluationEnd=a.evaluationEnd, runEndReason=a.runEndReason,
        selfReportedRegister=a.selfReportedRegister, registerEstimate='unclear',
        verificationMethod='same_reference_and_evaluation_crepe_pyin', observedMidis=[], frames=[])
    result['pitchCandidates'] = [c.model_dump() for c in a.pitchCandidates]
    if a.probeStage is not None:
        result.update(probeStage=a.probeStage, anchorMidi=a.anchorMidi)
    if a.evaluationStart is None:
        result['status'] = 'insufficient_quality' if a.clientStatus == 'insufficient_quality' else 'unmeasured'
        return result
    for f in frames:
        # Pitch evidence remains independent of Android YIN and uses unchanged model cutoffs.
        pitch_ok = (f.get('baseVoiced', f.get('voiced', False)) and f.get('periodicity', 0) >= .62)
        f['agreement'] = bool(pitch_ok and f.get('pyinVoiced', False) and f.get('rawMidi') is not None and
            f.get('pyinMidi') is not None and abs(f['rawMidi']-f['pyinMidi']) <= .5)
        f['voiced'] = bool(pitch_ok and f['rmsLow'] >= floor)
        f['clarity'] = 1.0 if pitch_ok and f['agreement'] else 0.0
    m, quality = evaluate(frames, a.targetMidi, anchor, a.referenceStart, a.referenceEnd,
                          a.evaluationStart, a.evaluationEnd, floor, revised=revised)
    result['quality'] = quality
    result['frames'] = [f for f in frames if f['t'] + f['duration'] > a.referenceStart and f['t'] < a.evaluationEnd]
    if m is None or quality.get('inputUncertain'):
        result['status'] = 'insufficient_quality'
        return result
    device_note = a.metrics.creditedMidi if a.metrics else None
    conflict = (passes(m) != (a.clientStatus == 'passed') or m['creditedMidi'] != device_note or
                abs(m['referenceMidi']-a.referenceMidi) > .45 + 1e-8 or bool(quality.get('prematureEnd')))
    result.update(status='needs_review' if conflict else 'passed' if passes(m) else 'not_passed',
                  metrics=m, creditedMidi=m['creditedMidi'], serverProgressQualified=passes(m) and not conflict and a.advanceReason != 'anchor_reset',
                  serverReferenceMidi=m['referenceMidi'], observedMidis=[m['creditedMidi']] if m['creditedMidi'] is not None else [])
    return result


def analyze(path, submission, previous=None):
    results = []
    floor = max(.001, submission.noiseRms * 3.16)
    with sf.SoundFile(path) as recording, tempfile.TemporaryDirectory(prefix='range-v5-') as directory:
        if recording.channels != 1 or recording.samplerate not in (16000,44100,48000):
            raise ValueError('16/44.1/48 kHz mono WAV recording required')
        if abs(len(recording)/recording.samplerate-submission.durationSeconds) > .15:
            raise ValueError('recording duration does not match metadata')
        temporary = Path(directory)/'response.wav'
        # Whole-session register analysis stays bounded and remains an optional estimate.
        try:
            registers = response_register(recording, temporary, submission.attempts, submission.noiseRms)
        except ValueError:
            raise
        except Exception:
            registers = {}
        for a in submission.attempts:
            frames = []
            if a.evaluationStart is not None:
                left = max(math.ceil(a.responseStart*100)/100, math.floor((a.referenceStart-.5)*100)/100)
                right = min(a.responseEnd, a.evaluationEnd+.5)
                frames, *_ = decode_clip(recording, temporary, a, left, right, submission.noiseRms)
                frames = device_clock(frames, a.referenceStart, a.evaluationEnd, recording.samplerate)
                aligned_levels(recording, frames)
            anchor = a.anchorMidi if submission.protocolVersion in ('guided-range-v6', 'guided-range-v7') else submission.startMidi
            r = verify(a, frames, anchor if anchor is not None else a.targetMidi, floor, revised=submission.protocolVersion == 'guided-range-v7')
            estimate = registers.get(a.attemptId, 'unclear')
            if a.selfReportedRegister != 'unknown' and a.selfReportedRegister != estimate:
                estimate = 'unclear'
            r['registerEstimate'] = estimate
            results.append(r)
    return build_report(submission.model_dump(), results, previous)
