"""Torch-free helpers shared by the analysis engine and offline importers.

`engine.py` pulls in torch / librosa / separation models at import time, which makes it unusable from
lightweight batch tools (e.g. `import_stem_pitch.py`).  The pure-numpy pieces those tools need live here and
`engine.py` re-exports them, so the numbers are computed by exactly one implementation.
"""
from __future__ import annotations

from typing import Any, Callable

import numpy as np

NOTE_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]


def midi_to_name(midi: int) -> str:
    return f"{NOTE_NAMES[midi % 12]}{midi // 12 - 1}"


def _weighted_percentile(notes: list[dict[str, Any]], percentile: float) -> int:
    ordered = sorted(notes, key=lambda item: item["midi"])
    total = sum(item["duration"] for item in ordered)
    target = total * percentile
    cursor = 0.0
    for item in ordered:
        cursor += item["duration"]
        if cursor >= target:
            return item["midi"]
    return ordered[-1]["midi"]


def _analyze_range(notes: list[dict[str, Any]]) -> dict[str, Any]:
    """Range summary of a transcribed melody: extremes, duration-weighted 5–95 % tessitura (`coreRange`),
    low/middle/high time shares and a length-weighted confidence (0–100)."""
    if not notes:
        raise RuntimeError("보컬에서 유효한 음표를 감지하지 못했습니다.")
    lowest_midi = min(item["midi"] for item in notes)
    highest_midi = max(item["midi"] for item in notes)
    core_low = _weighted_percentile(notes, 0.05)
    core_high = _weighted_percentile(notes, 0.95)
    span = max(3, highest_midi - lowest_midi + 1)
    low_end = lowest_midi + span / 3
    middle_end = lowest_midi + span * 2 / 3
    zones = {"low": 0.0, "middle": 0.0, "high": 0.0}
    total_duration = sum(item["duration"] for item in notes)
    weighted_confidence = 0.0
    for item in notes:
        zone = "low" if item["midi"] < low_end else "middle" if item["midi"] < middle_end else "high"
        zones[zone] += item["duration"]
        weighted_confidence += item["confidence"] * item["duration"]
    ratios = {key: round(value / total_duration * 100) for key, value in zones.items()}
    ratios["middle"] += 100 - sum(ratios.values())
    return {
        "lowest": midi_to_name(lowest_midi),
        "highest": midi_to_name(highest_midi),
        "coreRange": f"{midi_to_name(core_low)}–{midi_to_name(core_high)}",
        "lowestMidi": lowest_midi,
        "highestMidi": highest_midi,
        "ratios": ratios,
        "confidence": round(weighted_confidence / total_duration * 100),
        "notes": notes,
        "noteCount": len(notes),
        "voicedDuration": round(total_duration, 2),
    }


# --- beat grid → beat position (same arithmetic as pitch_engine._time_to_beat / make_time_to_beat) -----------

def time_to_beat(time_value: float, beat_times: np.ndarray) -> float:
    positions = np.arange(len(beat_times), dtype=float)
    if len(beat_times) < 2:
        return time_value * 2.0
    if time_value < beat_times[0]:
        interval = max(1e-3, beat_times[1] - beat_times[0])
        return (time_value - beat_times[0]) / interval
    if time_value > beat_times[-1]:
        interval = max(1e-3, beat_times[-1] - beat_times[-2])
        return positions[-1] + (time_value - beat_times[-1]) / interval
    return float(np.interp(time_value, beat_times, positions))


def make_time_to_beat(beat_grid: dict[str, Any]) -> Callable[[float], float]:
    beat_times = np.asarray(beat_grid["beatTimes"], dtype=float)
    offset = float(beat_grid["offsetBeats"])
    return lambda seconds: time_to_beat(seconds, beat_times) - offset
