"""Recompute longest self-relative runs and brief observed notes from streamed original responses."""
import math
import tempfile
from pathlib import Path
import soundfile as sf
from guided_range_v2 import response_register
from relative_hold import Tracker, passes
from range_evaluator import build_report


def analyze(path, submission, previous=None):
    floor = max(.001, submission.noiseRms * 3.16)
    trackers = {a.attemptId: Tracker(a.targetMidi, submission.startMidi or a.targetMidi, floor) for a in submission.attempts}
    onsets = {}
    usable = set()

    def consume(a, frames):
        tracker = trackers[a.attemptId]
        for frame in frames:
            if frame['clarity'] >= .8 and frame['rmsLow'] >= floor:
                onsets.setdefault(a.attemptId, frame['t'])
            start = onsets.get(a.attemptId)
            if start is None or frame['t'] + frame['duration'] <= start + .2:
                continue
            left = max(frame['t'], start + .2)
            f = dict(frame, t=left, duration=min(a.responseEnd, frame['t'] + frame['duration']) - left)
            # A neural pitch without corroborating pYIN evidence is uncertainty, not a short failed hold.
            if not f.get('agreement', False):
                f['clarity'] = 0.0
            elif f['clarity'] >= .8 and f['rmsLow'] >= floor:
                usable.add(a.attemptId)
            tracker.accept(f)

    with sf.SoundFile(path) as recording, tempfile.TemporaryDirectory(prefix='range-v4-') as directory:
        if recording.channels != 1 or recording.samplerate not in (16000, 44100, 48000):
            raise ValueError('16/44.1/48 kHz mono WAV recording required')
        if abs(len(recording) / recording.samplerate - submission.durationSeconds) > .15:
            raise ValueError('recording duration does not match metadata')
        try:
            registers = response_register(recording, Path(directory) / 'response.wav', submission.attempts,
                                          submission.noiseRms, on_frames=consume)
        except ValueError:
            raise
        except Exception:
            # Decode failures must not silently become an empty completed report.
            raise RuntimeError('Could not verify the complete recorded response')
    groups = {}
    for a in submission.attempts:
        groups.setdefault(a.visitId, []).append(a)
    results = []
    for group in groups.values():
        scored = [a for a in group if trackers[a.attemptId].best is not None]
        selected = max(scored, key=lambda a: trackers[a.attemptId].best['metrics']['continuousHoldSeconds']) if scored else group[-1]
        device = next(a for a in group if a.selectedForVisit)
        server_record = trackers[selected.attemptId].best
        server_pass = passes(server_record['metrics'] if server_record else None)
        conflict = server_pass != (device.clientStatus == 'passed')
        for a in group:
            tracker = trackers[a.attemptId]
            record = tracker.best
            m = record['metrics'] if record else None
            status = ('passed' if passes(m) else 'not_passed') if m else ('insufficient_quality' if a.clientStatus != 'unmeasured' and a.attemptId not in usable else 'unmeasured')
            if a == selected and conflict:
                status = 'needs_review' if m else 'insufficient_quality'
            estimate = registers.get(a.attemptId, 'unclear')
            if a.selfReportedRegister != 'unknown' and estimate != a.selfReportedRegister:
                estimate = 'unclear'
            results.append(dict(attemptId=a.attemptId, visitId=a.visitId, targetMidi=a.targetMidi,
                creditedMidi=m['creditedMidi'] if m else None, status=status, metrics=m,
                selectedForVisit=a == selected, clientSelectedForVisit=a.selectedForVisit,
                clientEvaluationStart=a.evaluationStart, clientEvaluationEnd=a.evaluationEnd,
                evaluationStart=record['start'] if record else None, evaluationEnd=record['end'] if record else None,
                observedMidis=sorted(tracker.observed), advanceReason=a.advanceReason,
                progressQualified=a.progressQualified, serverProgressQualified=server_pass,
                searchOutcome=a.searchOutcome, registerEstimate=estimate, selfReportedRegister=a.selfReportedRegister,
                verificationMethod='whole_response_crepe_pyin', frames=[{key:f.get(key) for key in (
                    't','duration','f0','rawMidi','periodicity','voiced','rmsLow','clippedRatio',
                    'pyinMidi','pyinVoiced','agreement')} for f in record['frames']] if record else []))
    return build_report(submission.model_dump(), results, previous)
