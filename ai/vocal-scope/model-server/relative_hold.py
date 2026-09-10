"""v4: longest uninterrupted self-referenced run, independent of pitch-matching occupancy."""
import math
from range_evaluator import weighted_median

FULL_SECONDS = 2.5
PASS_SECONDS = 1.0


def credit(actual, target, anchor):
    if not math.isfinite(actual) or abs(actual - target) > 1 + 1e-8:
        return None
    value = math.floor(actual + 1e-8) if target > anchor else math.ceil(actual - 1e-8) if target < anchor else math.floor(actual + .5)
    return value if 36 <= value <= 84 and abs(value - target) <= 1 else None


def passes(metrics):
    return bool(metrics and metrics.get("creditedMidi") is not None and
                metrics["continuousHoldSeconds"] >= PASS_SECONDS - 1e-8 and metrics["clippedRatio"] <= .01 + 1e-8)


class Tracker:
    def __init__(self, target, anchor, floor):
        self.target, self.anchor, self.floor = target, anchor, floor
        self.covered = -math.inf
        self.observed = set()
        self.best = self.current = None
        self.reset()

    def reset(self):
        self.run = []
        self.run_frames = []
        self.reference = self.end = self.current = None

    def accept(self, f):
        if not math.isfinite(f['t']) or not math.isfinite(f['duration']) or f['duration'] <= 0:
            return
        left = max(f['t'], self.covered)
        right = f['t'] + min(f['duration'], .04)
        if right <= left:
            return
        self.covered = right
        hz = f['f0']
        valid = math.isfinite(hz) and 60 <= hz <= 1150 and f['clarity'] >= .8 and f['rmsLow'] >= self.floor
        midi = 69 + 12 * math.log2(hz / 440) if valid else None
        if midi is None or abs(midi - self.target) > 1 + 1e-8 or f.get('clippedRatio', 0) > .01 + 1e-8:
            self.reset()
            return
        observed = credit(midi, self.target, self.anchor)
        if observed is None:
            self.reset()
            return
        self.observed.add(observed)
        if self.end is None or left - self.end > 1e-8 or abs(midi - self.reference) * 100 > 45 + 1e-8:
            self.reset()
            self.reference, self.start = midi, left
        limit = min(right, self.start + FULL_SECONDS)
        if limit <= left:
            return
        self.run.append((midi, limit - left))
        self.run_frames.append(dict(f, t=left, duration=limit-left))
        self.end = limit
        actual = weighted_median(self.run)
        errors = [(abs(m - actual) * 100, dt) for m, dt in self.run]
        duration = limit - self.start
        spread = math.sqrt(sum(e*e*dt for e,dt in errors) / duration)
        time = 50 * min(1, duration / FULL_SECONDS)
        steadiness = 50 * max(0, 1 - spread / 45) * min(1, duration / PASS_SECONDS)
        metrics = dict(medianAbsErrorCents=weighted_median(errors), pitchSpreadCents=spread,
                       inTuneRatio=1.0, voicedRatio=1.0, longestHoldSeconds=duration,
                       clippedRatio=f.get('clippedRatio', 0), continuousHoldSeconds=duration,
                       actualMidi=actual, creditedMidi=credit(actual, self.target, self.anchor),
                       stabilityScore=time + steadiness, timeScore=time, steadinessScore=steadiness)
        self.current = dict(start=self.start, end=limit, metrics=metrics)
        if self.best is None or duration > self.best['metrics']['continuousHoldSeconds'] + 1e-8:
            self.best = dict(self.current, frames=list(self.run_frames))


def evaluate(frames, target, anchor, start, end, floor):
    tracker = Tracker(target, anchor, floor)
    for frame in frames:
        left, right = max(start, frame['t']), min(end, frame['t'] + min(frame['duration'], .04))
        if right > left:
            tracker.accept(dict(frame, t=left, duration=right-left))
    return tracker
