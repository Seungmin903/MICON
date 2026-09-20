"""Target-free three-stage range verification; never infer a note from a target tone."""
from __future__ import annotations

import math
import tempfile
from pathlib import Path
from types import SimpleNamespace

import numpy as np

# Each sweep stage is a fixed mission; comfort still ends on a held note.
MISSION_SECONDS = 15.0
COMFORT_HOLD_SECONDS = .5
SWEEP_HOLD_SECONDS = .3
ENDPOINT_OFFSET_SECONDS = .5


def validate_meta(meta: dict, duration: float) -> list[dict]:
    stages = meta.get("stages")
    if (meta.get("protocolVersion") != "guided-range-v8" or meta.get("mode") != "basic"
            or meta.get("parentAnalysisId") is not None or not isinstance(stages, list)
            or len(stages) != 3):
        raise ValueError("invalid three-stage range metadata")
    previous = 0.0
    for index, stage in enumerate(stages):
        if not isinstance(stage, dict) or stage.get("kind") != ("comfort", "down", "up")[index]:
            raise ValueError("range stages are out of order")
        try:
            start, end, hold_start, hold_end, origin, endpoint = (
                float(stage[key]) for key in
                ("start", "end", "holdStart", "holdEnd", "startMidi", "endpointMidi"))
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("invalid stage time or pitch") from error
        sweep = index > 0
        length = end - start
        bad_length = (not MISSION_SECONDS - .1 <= length <= MISSION_SECONDS + .6) if sweep else length > 25
        minimum_hold = (SWEEP_HOLD_SECONDS if sweep else COMFORT_HOLD_SECONDS) - .05
        if (not all(math.isfinite(value) for value in
                    (start, end, hold_start, hold_end, origin, endpoint))
                or start < previous or bad_length or end > duration + .1
                or hold_start < start or hold_end > end or hold_end - hold_start < minimum_hold
                or sweep and hold_start - start < ENDPOINT_OFFSET_SECONDS - .05
                or not 36 <= origin <= 84 or not 36 <= endpoint <= 84
                or index == 1 and origin - endpoint < 2
                or index == 2 and endpoint - origin < 2):
            raise ValueError("stage bounds do not match recording")
        previous = end
    return stages


def _valid(frame: dict, floor: float) -> bool:
    pitch = frame.get("rawMidi")
    return (pitch is not None and math.isfinite(pitch) and 36 <= pitch <= 84
            and frame.get("voiced") is True and frame.get("agreement") is True
            and frame.get("rmsLow", 0) >= floor and frame.get("clippedRatio", 1) <= .01)


def _held_pitch(frames: list[dict], start: float, end: float, floor: float,
                minimum_span: float = COMFORT_HOLD_SECONDS) -> float:
    # A short steady run is enough; the model independently verifies its voice and pitch.
    # The span, not a frame count, is the guarantee, so the hop size stays free to change.
    selected = [f for f in frames if start - .02 <= f["t"] < end + .02]
    good = [f for f in selected if _valid(f, floor)]
    if len(good) < 12 or len(good) / max(1, len(selected)) < .88:
        raise ValueError("held note has insufficient verified voice")
    times = np.array([f["t"] for f in good])
    pitches = np.array([f["rawMidi"] for f in good])
    if times[-1] - times[0] < minimum_span - .07:
        raise ValueError("held note has insufficient verified voice")
    median = float(np.median(pitches))
    if (np.max(np.diff(times)) > .12 or np.quantile(pitches, .9) - np.quantile(pitches, .1) > 3.0
            or float(np.mean(np.abs(pitches - median) <= 1.5)) < .9):
        raise ValueError("held note is unstable")
    return float(np.median(pitches))


def evaluate_stages(meta: dict, frames_by_stage: list[list[dict]], duration: float) -> dict:
    stages = validate_meta(meta, duration)
    floor = max(.001, float(meta.get("noiseRms", 0)) * 3.16)
    held = []
    all_pitch = []
    for index, (stage, frames) in enumerate(zip(stages, frames_by_stage)):
        hold = _held_pitch(frames, float(stage["holdStart"]), float(stage["holdEnd"]), floor,
                           SWEEP_HOLD_SECONDS if index else COMFORT_HOLD_SECONDS)
        valid = [frame for frame in frames if _valid(frame, floor)]
        if index:
            beginning = [frame["rawMidi"] for frame in valid
                         if float(stage["start"]) <= frame["t"] < float(stage["start"]) + .5]
            if len(beginning) < 15:
                raise ValueError("sweep start has insufficient verified voice")
            origin = float(np.median(beginning))
            direction = -1 if index == 1 else 1
            if abs(origin - held[0]) > 1.5 or direction * (hold - origin) < 1.8 or len(valid) < 185:
                raise ValueError("sweep did not reach a held endpoint")
        held.append(hold)
        # Isolated pitch spikes never become a range boundary.
        pitches = np.array([frame["rawMidi"] for frame in valid])
        if len(pitches) >= 30:
            for offset in range(0, len(pitches) - 29, 10):
                all_pitch.append(float(np.median(pitches[offset:offset + 30])))
    if not all_pitch:
        raise ValueError("no verified range")
    low, high = min(all_pitch), max(all_pitch)
    stable_low, stable_high = held[1], held[2]
    if stable_low > stable_high:
        raise ValueError("held range endpoints are reversed")
    return {"protocolVersion": "guided-range-v8", "availability": "available",
            "lowMidi": round(min(low, stable_low), 2), "highMidi": round(max(high, stable_high), 2),
            "stableLowMidi": round(stable_low, 2), "stableHighMidi": round(stable_high, 2),
            "notes": []}


def analyze_free(path: Path, meta: dict) -> dict:
    import soundfile as sf
    from guided_range_v2 import decode_clip
    with sf.SoundFile(path) as recording:
        duration = len(recording) / recording.samplerate
        if recording.channels != 1 or recording.samplerate not in (16000, 44100, 48000):
            raise ValueError("16/44.1/48 kHz mono WAV recording required")
        if duration < float(meta["durationSeconds"]) - .1 or duration > 180.1:
            raise ValueError("recording duration does not match metadata")
        stages = validate_meta(meta, duration)
        frames_by_stage = []
        with tempfile.TemporaryDirectory(prefix="range-v8-") as temporary:
            clip = Path(temporary) / "stage.wav"
            for index, stage in enumerate(stages):
                frames, _, _, _, _ = decode_clip(recording, clip,
                    SimpleNamespace(attemptId=f"stage-{index}"),
                    float(stage["start"]), float(stage["end"]), float(meta["noiseRms"]))
                frames_by_stage.append(frames)
        return evaluate_stages(meta, frames_by_stage, duration)
