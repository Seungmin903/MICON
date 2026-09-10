"""Target-relative, time-weighted scoring. No tuning, quantization or octave folding."""
from __future__ import annotations

import math

VERSION = "guided-range-v1"
MIN_MIDI, MAX_MIDI = 36, 84


def weighted_median(items):
    if not items:
        return None
    items = sorted(items)
    half = sum(w for _, w in items) / 2
    total = 0.0
    for i, (value, weight) in enumerate(items):
        total += weight
        if abs(total - half) < 1e-9 and i + 1 < len(items):
            return (value + items[i + 1][0]) / 2
        if total >= half:
            return value
    return items[-1][0]


def evaluate(frames, target, start, end, floor):
    """Frames have t/duration/f0/clarity/rmsLow/clippedRatio; absent spans stay invalid."""
    assert end > start
    covered = start
    voiced_time = in_tune = clipped = hold = longest = 0.0
    last_good_end = None
    continuous = longest_continuous = 0.0
    continuous_end = None
    errors = []
    for f in frames:
        left = max(start, f["t"], covered)
        right = min(end, f["t"] + min(f["duration"], .04))
        if right <= left:
            continue
        covered = right
        dt = right - left
        clipped += max(0.0, min(1.0, f.get("clippedRatio", 0.0))) * dt
        hz = f["f0"]
        if not (math.isfinite(hz) and 60 <= hz <= 1150 and f["clarity"] >= .8 and f["rmsLow"] >= floor):
            continuous = 0.0
            continuous_end = None
            continue
        error = 1200 * math.log2(hz / (440 * 2 ** ((target - 69) / 12)))
        voiced_time += dt
        errors.append((error, dt))
        if abs(error) <= 45 + 1e-8:
            in_tune += dt
            if last_good_end is None or left - last_good_end > .1 + 1e-8:
                hold = 0.0
            hold += dt
            longest = max(longest, hold)
            last_good_end = right
            if f.get("clippedRatio", 0) <= .01 + 1e-8:
                if continuous_end is None or left - continuous_end > 1e-8:
                    continuous = 0.0
                continuous += dt
                continuous_end = right
                longest_continuous = max(longest_continuous, continuous)
            else:
                continuous = 0.0
                continuous_end = None
        else:
            continuous = 0.0
            continuous_end = None
    center = weighted_median(errors)
    spread = None if center is None else 1.4826 * weighted_median([(abs(e - center), w) for e, w in errors])
    duration = end - start
    return dict(medianAbsErrorCents=weighted_median([(abs(e), w) for e, w in errors]),
                pitchSpreadCents=spread, inTuneRatio=min(1.0, in_tune / duration),
                voicedRatio=min(1.0, voiced_time / duration), longestHoldSeconds=longest,
                clippedRatio=clipped / duration, continuousHoldSeconds=longest_continuous)


def passes(m):
    return (m is not None and m["medianAbsErrorCents"] is not None and m["pitchSpreadCents"] is not None
            and m["medianAbsErrorCents"] <= 25 + 1e-8 and m["pitchSpreadCents"] <= 20 + 1e-8
            and m["inTuneRatio"] >= .9 - 1e-8 and m["voicedRatio"] >= .9 - 1e-8
            and m["longestHoldSeconds"] >= 1.5 - 1e-8 and m["clippedRatio"] <= .01 + 1e-8)


def build_report(submission, results, previous=None):
    version = submission.get("protocolVersion", VERSION)
    modern = version in ("guided-range-v2", "guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")
    buffered = version in ("guided-range-v5", "guided-range-v6", "guided-range-v7")
    relative = version in ("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")
    if previous:
        if previous["protocolVersion"] != version:
            raise ValueError("이전 측정 버전이 달라 추가 측정할 수 없습니다.")
        results = previous.get("attemptResults", []) + results
    notes = []
    for midi in range(MIN_MIDI, MAX_MIDI + 1):
        attempts = [a for a in results if (a.get("creditedMidi") if relative and a.get("creditedMidi") is not None else a["targetMidi"]) == midi and a["status"] != "interrupted" and (not modern or a.get("selectedForVisit", True))]
        successes = [a for a in attempts if a["status"] == "passed"]
        failures = [a for a in attempts if a["status"] == "not_passed"]
        uncertain = [a for a in attempts if a["status"] in ("needs_review", "insufficient_quality")]
        if successes and failures:
            status = "inconsistent"
        elif any(a["status"] == "needs_review" for a in uncertain):
            status = "needs_review"
        elif len(successes) >= 2:
            status = "reconfirmed"
        elif successes:
            status = "passed"
        elif uncertain:
            status = "insufficient_quality"
        elif failures:
            status = "not_passed"
        else:
            status = "unmeasured"
        measured = [a for a in attempts if a.get("metrics") is not None and (not buffered or a['status'] in ('passed','not_passed'))]
        representative = (max(measured, key=lambda a: a["metrics"]["creditedHoldSeconds" if buffered else "continuousHoldSeconds" if relative else "inTuneRatio"]) if modern else measured[-1]) if measured else None
        registers = {a.get("registerEstimate", "unclear") for a in successes}
        estimates = registers.pop() if len(registers) == 1 else "unclear"
        notes.append(dict(midi=midi, status=status, metrics=representative["metrics"] if representative else None,
                          evaluationStart=representative.get("evaluationStart") if representative else None,
                          evaluationEnd=representative.get("evaluationEnd") if representative else None,
                          representativeAttemptId=representative.get("attemptId") if representative else None,
                          registerEstimate=estimates, selfReportedRegister=attempts[-1].get("selfReportedRegister", "unknown") if attempts else "unknown",
                          successfulAttempts=len(successes), attempts=len(attempts)))
    # Extrema record observed successes; uncertain/conflicting notes never bridge a stable interval.
    observed = [n["midi"] for n in notes if n["successfulAttempts"] > 0 and (version not in ("guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7") or n["status"] in ("passed", "reconfirmed"))]
    intervals = []
    for note in notes:
        if note["status"] not in ("passed", "reconfirmed"):
            continue
        m = note["midi"]
        if intervals and intervals[-1][1] + 1 == m:
            intervals[-1][1] = m
        else:
            intervals.append([m, m])
    sampling = submission.get("sampling")
    if version in ("guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7") and sampling is not None:
        sampling = dict(sampling, measuredNoteCount=sum(n["metrics"] is not None and n["status"] not in ("needs_review", "insufficient_quality") for n in notes))
    range_map = None
    if relative and not buffered:
        reached = [m for a in results for m in a.get("observedMidis", []) if 36 <= m <= 84]
        overall = [min(reached), max(reached)] if reached else None
        stable = [min(observed), max(observed)] if observed else None
        recommended = [stable[0] + 2, stable[1] - 2] if stable and stable[1] - stable[0] >= 4 else None
        range_map = dict(overall=overall, stable=stable, recommended=recommended)
        intervals = [stable] if stable else []
    available = bool(observed)
    if relative:
        available = available and bool(sampling and (sampling.get("basicCompleted") or sampling.get("refinementStatus") == "completed"))
    return dict(protocolVersion=version, notes=notes, sampling=sampling, rangeMap=range_map, lowMidi=min(observed) if observed else None,
                highMidi=max(observed) if observed else None, stableIntervals=intervals,
                stopReason=submission["stopReason"], lowerBoundaryStatus=submission["lowerBoundaryStatus"],
                upperBoundaryStatus=submission["upperBoundaryStatus"],
                availability="available" if available else ("partial" if modern and any(n["metrics"] is not None for n in notes) else "insufficient_data"), attemptResults=results,
                calibration=dict(noiseRms=submission.get("noiseRms"), referenceAHz=440,
                                 tuningCorrectionCents=0, transpositionSemitones=0, octaveFolding=False))
