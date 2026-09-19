"""v5 raw-frame evaluator. Fixed reference and exact client-selected completed interval."""
import math
from range_evaluator import weighted_median

EPS = 1e-8


def credit(actual, target, anchor):
    value = (math.floor(actual + EPS) if target > anchor else math.ceil(actual - EPS)
             if target < anchor else math.floor(actual + .5))
    return value if 36 <= value <= 84 else None


def valid(f, floor):
    return (math.isfinite(f['f0']) and 60 <= f['f0'] <= 1150 and
            math.isfinite(f['clarity']) and f['clarity'] >= .8 and
            math.isfinite(f['rmsLow']) and f['rmsLow'] >= floor and
            math.isfinite(f.get('clippedRatio', 0)) and f.get('clippedRatio', 0) <= .01 + EPS)


def passes(m):
    return bool(m and m['creditedMidi'] is not None and m['creditedHoldSeconds'] >= 1 - EPS
                and m['clippedRatio'] <= .01 + EPS)


def portions(frames, start, end):
    covered = start
    for f in frames:
        left, right = max(start, f['t'], covered), min(end, f['t'] + min(.04, f['duration']))
        if right > left + EPS:
            yield f, left, right, left - covered
            covered = right


def evaluate(frames, target, anchor, reference_start, reference_end, start, end, floor, *, revised=False):
    """Never seek a better server window, bridge missing input, or move the reference.

    Metrics cover the exact selected interval, including its penalty tail. If the server
    observes an earlier endpoint/input loss the caller must mark it uncertain/conflicting.
    """
    seed = []
    for f, left, right, _ in portions(frames, reference_start, reference_end):
        if valid(f, floor):
            midi = 69 + 12 * math.log2(f['f0'] / 440)
            if abs(midi - target) <= (2 if revised else 1) + EPS:
                seed.append((midi, right - left))
    if sum(dt for _, dt in seed) < .15 - EPS:
        return None, dict(reason='reference_uncertain', referenceValidSeconds=sum(dt for _, dt in seed))
    reference = weighted_median(seed)
    credited = buffer = outside = strict = strict_max = excursion = hard = clipped = 0.0
    pitches, core = [], []
    input_uncertain = False
    premature = None
    coverage = start
    for f, left, right, gap in portions(frames, start, end):
        dt = right - left
        coverage = right
        if gap > EPS or not valid(f, floor):
            input_uncertain = True
            strict = 0.0
            # This is not buffer and cannot be reconnected into one accepted run.
            continue
        midi = 69 + 12 * math.log2(f['f0'] / 440)
        cents = abs(midi - reference) * 100
        pitches.append((midi, dt)); clipped = max(clipped, f.get('clippedRatio', 0))
        if cents <= 45 + EPS:
            credited += dt; strict += dt; strict_max = max(strict_max, strict)
            excursion = hard = 0.0; core.append((midi, dt))
        else:
            strict = 0.0; excursion += dt
            if cents <= 100 + EPS:
                buffer += dt; hard = 0.0
            else:
                outside += dt; hard += dt
            timeout = 'outside_timeout' if hard >= .1 - EPS else 'buffer_timeout' if excursion >= .5 - EPS else None
            if timeout and right < end - EPS:
                premature = premature or dict(reason=timeout, t=right)
    input_uncertain |= coverage < end - EPS
    details = dict(referenceMidi=reference, referenceValidSeconds=sum(dt for _, dt in seed),
                   inputUncertain=input_uncertain, prematureEnd=premature)
    if not core:
        return None, details
    actual = weighted_median(core)
    spread = math.sqrt(sum(((m-actual)*100)**2*dt for m,dt in pitches) / sum(dt for _,dt in pitches))
    reference_rms = math.sqrt(sum(((m-reference)*100)**2*dt for m,dt in pitches) / sum(dt for _,dt in pitches))
    time = 50 * min(1, credited / 2.5)
    steady = time * max(0, 1-reference_rms/45) if revised else 50 * max(0, 1-spread/45) * min(1, credited)
    penalty = min(20, 40*(buffer + 2*outside))
    m = dict(medianAbsErrorCents=weighted_median([(abs(m-reference)*100,dt) for m,dt in pitches]),
             pitchSpreadCents=spread, inTuneRatio=credited/(end-start), voicedRatio=(credited+buffer+outside)/(end-start),
             longestHoldSeconds=strict_max, continuousHoldSeconds=strict_max, clippedRatio=clipped,
             actualMidi=actual, creditedMidi=credit(actual,target,anchor), creditedHoldSeconds=credited,
             bufferSeconds=buffer, outsideSeconds=outside, excursionPenalty=penalty, referenceMidi=reference,
             timeScore=time, steadinessScore=steady, stabilityScore=max(0,time+steady-penalty))
    if revised:
        m['referenceRmsCents'] = reference_rms
    return m, details
