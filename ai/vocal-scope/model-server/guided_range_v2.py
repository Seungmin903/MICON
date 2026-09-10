"""Unbounded-duration measurement: bounded audio reads and exact selected-window verification.

The v1 decoder is reused on response-only clips (no reference tone or neighbouring target).
Only selected-window frame evidence enters the report; the original WAV remains the audit source.
"""
from __future__ import annotations

import math
import tempfile
from pathlib import Path
from types import SimpleNamespace
import numpy as np
import soundfile as sf
from range_evaluator import build_report, evaluate, passes

BLOCK_SECONDS = 30


def decode_clip(recording, temporary, attempt, start, end, noise):
    """Seek by the original sample clock. A single decode never exceeds 30 seconds."""
    rate = recording.samplerate
    recording.seek(round(start * rate))
    samples = recording.read(round((end - start) * rate), dtype="float32", always_2d=True)
    if not np.isfinite(samples).all():
        raise ValueError("audio contains invalid samples")
    sf.write(temporary, samples, rate, subtype="FLOAT")
    from guided_range import extract_frames
    local_attempt = SimpleNamespace(attemptId=attempt.attemptId, responseStart=0.0,
                                    responseEnd=len(samples) / rate)
    meta = SimpleNamespace(durationSeconds=len(samples) / rate, noiseRms=noise, attempts=[local_attempt])
    frames, compared, agreement, octave, registers = extract_frames(temporary, meta)
    for f in frames:
        f["t"] += start
    return frames, compared, agreement, octave, registers


def response_register(recording, temporary, attempts, noise, on_frames=None):
    """Keep whole-session register statistics while streaming waveform and decoder blocks."""
    floor = max(.001, noise * 3.16)
    rate = recording.samplerate
    length = math.ceil(len(recording) / rate * 100) + 1
    tracks = {}
    # Evidence is disk-backed; no full-session waveform or CREPE probability matrix is retained.
    for name, dtype in (("f0", "float32"), ("voiced", "bool"), ("periodicity", "float32"),
                        ("agreement", "bool"), ("level", "float32")):
        tracks[name] = np.memmap(temporary.parent / (name + ".bin"), mode="w+", dtype=dtype, shape=(length,))
        tracks[name][:] = -140 if name == "level" else 0
    notes = []
    try:
        for attempt in attempts:
            start = attempt.responseStart
            while start < attempt.responseEnd - .1:
                # Decoder frame clocks start on a shared 10 ms grid.
                start = math.ceil(start * 100 - 1e-7) / 100
                end = min(attempt.responseEnd, start + BLOCK_SECONDS)
                recording.seek(round(start * rate))
                samples = recording.read(round((end - start) * rate), dtype="float32", always_2d=True)
                if not np.isfinite(samples).all():
                    raise ValueError("audio contains invalid samples")
                if len(samples) and float(np.sqrt(np.mean(samples ** 2))) >= floor:
                    frames, _, _, _, _ = decode_clip(recording, temporary, attempt, start, end, noise)
                    if on_frames is not None:
                        on_frames(attempt, [f for f in frames if start <= f['t'] < end])
                    for f in frames:
                        i = round(f["t"] * 100)
                        if i >= length or f["t"] >= end:
                            continue
                        for name in ("f0", "voiced", "periodicity", "agreement"):
                            tracks[name][i] = f.get(name, 0)
                        tracks["level"][i] = 20 * math.log10(max(f.get("rmsLow", 0), 1e-7))
                start = end
            lo, hi = math.ceil(attempt.responseStart * 100), math.floor(attempt.responseEnd * 100)
            actual = tracks["f0"][lo:hi][tracks["voiced"][lo:hi]]
            if len(actual):
                notes.append(dict(id=attempt.attemptId, midi=float(np.median(69 + 12*np.log2(actual/440))),
                                  sourceStart=attempt.responseStart, sourceEnd=attempt.responseEnd))
        if not notes:
            return {}
        from register_engine import analyze_register
        try:
            analyze_register(Path(recording.name), tracks["f0"], tracks["voiced"], tracks["periodicity"],
                             tracks["agreement"], notes, level_db=tracks["level"], stream_audio=True)
        except Exception:
            if on_frames is None:
                raise
            # v4 pitch evidence is complete already; optional register failure cannot erase it.
            return {}
        return {n["id"]: n.get("register", "unclear") if n.get("registerConfidence", 0) >= .6 else "unclear" for n in notes}
    finally:
        for track in tracks.values():
            track.flush()
            track._mmap.close()


def analyze(path, submission, previous=None):
    results = []
    floor = max(.001, submission.noiseRms * 3.16)
    with sf.SoundFile(path) as recording, tempfile.TemporaryDirectory(prefix="range-window-") as directory:
        if recording.channels != 1 or recording.samplerate not in (16000, 44100, 48000):
            raise ValueError("16/44.1/48 kHz mono WAV recording required")
        seconds = len(recording) / recording.samplerate
        if abs(seconds - submission.durationSeconds) > .15:
            raise ValueError("recording duration does not match metadata")
        temporary = Path(directory) / "response.wav"
        # The label remains an estimate; ambiguous estimates never affect pitch acceptance.
        try:
            registers = response_register(recording, temporary, submission.attempts, submission.noiseRms)
        except ValueError:
            raise
        except Exception:
            registers = {}
        def verify_window(a, start, end):
            left = max(math.ceil(a.responseStart * 100) / 100, math.floor((start - .5) * 100) / 100)
            right = min(a.responseEnd, end + .5)
            frames, compared, agreement, octave, _ = decode_clip(recording, temporary, a, left, right, submission.noiseRms)
            m = evaluate(frames, a.targetMidi, start, end, floor)
            lo = max(0, math.ceil((start - left) * 100 - 1e-7))
            hi = min(len(compared), math.floor((end - left) * 100 + 1e-7))
            n = int(compared[lo:hi].sum())
            agree = float(agreement[lo:hi].sum() / n) if n else 0.0
            octaves = float(octave[lo:hi].sum() / n) if n else 0.0
            quality = n >= 100 and agree >= .8 and octaves <= .1 and m["clippedRatio"] <= .01 + 1e-8
            return m, quality, dict(comparedSeconds=n * .01, agreementRatio=agree, octaveMismatchRatio=octaves), frames

        v3 = submission.protocolVersion == "guided-range-v3"
        progress = {}
        for a in submission.attempts:
            if v3 and a.progressEvaluationStart is not None:
                checked = verify_window(a, a.progressEvaluationStart, a.progressEvaluationEnd)
                progress[a.visitId] = (a, checked)
        for a in submission.attempts:
            result = dict(attemptId=a.attemptId, visitId=a.visitId, targetMidi=a.targetMidi,
                          advanceReason=a.advanceReason, progressQualified=a.progressQualified,
                          searchOutcome=a.searchOutcome, progressEvaluationStart=a.progressEvaluationStart,
                          progressEvaluationEnd=a.progressEvaluationEnd,
                          selectedForVisit=a.selectedForVisit, status=a.clientStatus, metrics=None,
                          registerEstimate=registers.get(a.attemptId, "unclear"),
                          selfReportedRegister=a.selfReportedRegister,
                          evaluationStart=a.evaluationStart, evaluationEnd=a.evaluationEnd, frames=[])
            if a.selfReportedRegister != "unknown" and result["registerEstimate"] != a.selfReportedRegister:
                result["registerEstimate"] = "unclear"
            if a.evaluationStart is None or not a.selectedForVisit:
                results.append(result)
                continue
            evidence = progress.get(a.visitId)
            same = evidence is not None and evidence[0].attemptId == a.attemptId and evidence[0].progressEvaluationStart == a.evaluationStart
            m, quality, details, frames = evidence[1] if same else verify_window(a, a.evaluationStart, a.evaluationEnd)
            server_pass = passes(m)
            if not quality:
                result["status"] = "insufficient_quality"
            elif server_pass != (a.clientStatus == "passed"):
                result["status"] = "needs_review"
            else:
                result["status"] = "passed" if server_pass else "not_passed"
            result.update(metrics=m if quality else None, quality=details,
                          serverProgressQualified=quality and m["inTuneRatio"] >= .5 - 1e-8)
            if v3:
                result["serverProgressQualified"] = False
                if evidence is not None:
                    source, (pm, pq, pd, _) = evidence
                    qualified = pq and pm["continuousHoldSeconds"] >= 1 - 1e-8
                    result.update(serverProgressQualified=qualified, progressMetrics=pm if pq else None,
                                  progressQuality=pd, progressEvidenceAttemptId=source.attemptId,
                                  progressEvaluationStart=source.progressEvaluationStart,
                                  progressEvaluationEnd=source.progressEvaluationEnd)
                    if not pq:
                        result["status"] = "insufficient_quality"
                    elif not qualified and quality:
                        result["status"] = "needs_review"
            result["frames"] = [{k: f.get(k) for k in (
                "t", "duration", "f0", "rawMidi", "periodicity", "voiced", "rmsLow", "clippedRatio",
                "pyinMidi", "pyinVoiced", "agreement")}
                for f in frames if a.evaluationStart <= f["t"] < a.evaluationEnd]
            results.append(result)
    return build_report(submission.model_dump(), results, previous)
