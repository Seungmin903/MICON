"""Reuse VocalScope F0/voice-register models without transcription, tuning or beat analysis."""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
import soundfile as sf

from range_evaluator import build_report, evaluate, passes
from range_models import Submission


def extract_frames(path: Path, submission: Submission):
    import librosa
    import torch
    import pitch_engine as pitch

    original, rate = sf.read(path, dtype="float32", always_2d=True)
    if rate not in (16000, 44100, 48000) or original.shape[1] != 1:
        raise ValueError("16/44.1/48 kHz mono WAV recording required")
    original = original[:, 0]
    seconds = len(original) / rate
    if seconds < submission.durationSeconds - .1 or seconds > 91:
        raise ValueError("recording duration does not match metadata")
    if not np.isfinite(original).all():
        raise ValueError("audio contains invalid samples")
    y = librosa.resample(original, orig_sr=rate, target_sr=16000) if rate != 16000 else original
    device = "cuda" if torch.cuda.is_available() else "cpu"
    probabilities = pitch._crepe_probabilities(y, device, 60.0, 1150.0)
    length = len(probabilities)
    periodicity = probabilities.max(axis=1)
    rms = pitch._match_length(pitch._frame_rms(y), length, 0.0)
    dbfs = 20 * np.log10(np.maximum(rms, 1e-7))
    base_voiced, _, _ = pitch._detect_voice_activity(dbfs, periodicity, None)
    midi = np.full(length, np.nan)
    pyin_midi = np.full(length, np.nan)
    pyin_voiced = np.zeros(length, bool)
    voiced = np.zeros(length, bool)
    floor = max(.001, submission.noiseRms * 3.16)
    # Decode each response separately: cues and target transitions cannot smooth a response into tune.
    for attempt in submission.attempts:
        left = max(0, math.ceil(attempt.responseStart * 100))
        right = min(length, math.floor(attempt.responseEnd * 100))
        if right <= left:
            continue
        decoded = pitch._local_average_midi(probabilities[left:right], pitch._viterbi_bins(probabilities[left:right]))
        cross, flag, _ = pitch._pyin_segment(y, left, right, decoded)
        # No target-directed octave correction. Disagreement is uncertainty, not permission to fold.
        midi[left:right] = decoded
        pyin_midi[left:right] = cross
        pyin_voiced[left:right] = flag
        voiced[left:right] = base_voiced[left:right] & (periodicity[left:right] >= .62) & (rms[left:right] >= floor)
    delta = np.abs(midi - pyin_midi)
    compared = voiced & pyin_voiced & np.isfinite(delta)
    agreement = compared & (delta <= .5)
    octave = compared & (np.abs(delta - 12) <= .7)
    f0 = 440 * np.power(2.0, (midi - 69) / 12)
    frames = []
    for i in range(length):
        # Clipping measured on original PCM, before any resampling or normalization.
        a, b = int(i * rate / 100), min(len(original), int((i + 1) * rate / 100))
        clip = float(np.mean(np.abs(original[a:b]) >= .99)) if b > a else 0.0
        frames.append(dict(t=i / 100, duration=.01, f0=float(f0[i]) if np.isfinite(f0[i]) else 0.0,
                           rawMidi=float(midi[i]) if np.isfinite(midi[i]) else None,
                           periodicity=float(periodicity[i]), voiced=bool(voiced[i]), baseVoiced=bool(base_voiced[i]),
                           pyinMidi=float(pyin_midi[i]) if np.isfinite(pyin_midi[i]) else None,
                           pyinVoiced=bool(pyin_voiced[i]), agreement=bool(agreement[i]),
                           clarity=1.0 if voiced[i] else 0.0, rmsLow=float(rms[i]), clippedRatio=clip))
    registers = {}
    try:
        from register_engine import analyze_register
        notes = []
        for a in submission.attempts:
            lo, hi = math.ceil(a.responseStart * 100), math.floor(a.responseEnd * 100)
            actual = midi[lo:hi][voiced[lo:hi]]
            if len(actual):
                notes.append(dict(id=a.attemptId, midi=float(np.median(actual)), sourceStart=a.responseStart,
                                  sourceEnd=a.responseEnd, duration=a.responseEnd - a.responseStart))
        # Existing rule uses session-relative pitch statistics. Labels remain estimates only.
        analyze_register(path, f0, voiced, periodicity, agreement, notes, level_db=dbfs)
        for note in notes:
            registers[note["id"]] = note.get("register", "unclear") if note.get("registerConfidence", 0) >= .6 else "unclear"
    except Exception:
        # Voice register is an optional annotation; never reject pitch results for its failure.
        pass
    return frames, compared, agreement, octave, registers


def analyze(path: Path, submission: Submission, previous=None):
    if submission.protocolVersion in ("guided-range-v5", "guided-range-v6", "guided-range-v7"):
        from guided_range_v5 import analyze as analyze_v5
        return analyze_v5(path, submission, previous)
    if submission.protocolVersion == "guided-range-v4":
        from guided_range_v4 import analyze as analyze_v4
        return analyze_v4(path, submission, previous)
    if submission.protocolVersion in ("guided-range-v2", "guided-range-v3"):
        from guided_range_v2 import analyze as analyze_v2
        return analyze_v2(path, submission, previous)
    if not submission.attempts:
        return build_report(submission.model_dump(), [], previous)
    frames, compared, agreement, octave, registers = extract_frames(path, submission)
    results = []
    floor = max(.001, submission.noiseRms * 3.16)
    for a in submission.attempts:
        result = dict(attemptId=a.attemptId, targetMidi=a.targetMidi, status="interrupted", metrics=None,
                      registerEstimate=registers.get(a.attemptId, "unclear"), selfReportedRegister=a.selfReportedRegister)
        # Keep uncorrected evidence on the same audio clock for later audit/re-analysis.
        response = [f for f in frames if a.responseStart <= f["t"] < a.responseEnd]
        result["frames"] = [{key: f.get(key) for key in (
            "t", "duration", "f0", "rawMidi", "periodicity", "voiced", "rmsLow", "clippedRatio",
            "pyinMidi", "pyinVoiced", "agreement")} for f in response]
        if a.selfReportedRegister != "unknown" and result["registerEstimate"] not in (a.selfReportedRegister, "unclear"):
            result["registerEstimate"] = "unclear"
        if a.clientStatus == "interrupted":
            results.append(result)
            continue
        windows = []
        if a.evaluationStart is not None:
            windows = [(a.evaluationStart, a.evaluationEnd)]
        else:
            onset = next((f["t"] for f in response if f["clarity"] >= .8 and f["rmsLow"] >= floor), None)
            if onset is not None:
                windows = [(s / 100, s / 100 + 2) for s in range(math.ceil((onset + .2) * 100), math.floor((a.responseEnd - 2) * 100) + 1, 2)]
        candidates = [(evaluate(response, a.targetMidi, s, e, floor), s, e) for s, e in windows]
        if candidates:
            m, start, end = max(candidates, key=lambda x: (passes(x[0]), x[0]["inTuneRatio"], x[0]["voicedRatio"]))
        else:
            start, end = a.responseStart, a.responseEnd
            m = evaluate(response, a.targetMidi, start, end, floor)
        lo, hi = math.ceil(start * 100), math.floor(end * 100)
        n = int(compared[lo:hi].sum())
        agree = float(agreement[lo:hi].sum() / n) if n else 0.0
        octave_ratio = float(octave[lo:hi].sum() / n) if n else 0.0
        quality_ok = n >= 50 and n * .01 >= .5 * (end - start) and agree >= .8 and octave_ratio <= .1 and m["clippedRatio"] <= .01
        server_pass = bool(candidates) and passes(m)
        if not quality_ok:
            result["status"] = "insufficient_quality"
        elif server_pass != (a.clientStatus == "passed"):
            result["status"] = "needs_review"
        else:
            result["status"] = "passed" if server_pass else "not_passed"
        result.update(metrics=m if quality_ok else None, evaluationStart=start, evaluationEnd=end,
                      quality=dict(comparedSeconds=n * .01, agreementRatio=agree, octaveMismatchRatio=octave_ratio))
        results.append(result)
    return build_report(submission.model_dump(), results, previous)
