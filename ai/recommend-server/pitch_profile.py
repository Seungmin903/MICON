"""Pure, JSON-safe vocal-range summaries shared by analysis and recommendation."""

from __future__ import annotations

from typing import Any, Iterable, Mapping

import numpy as np


MIDI_MIN = 36
MIDI_MAX = 95
PROFILE_SCHEMA_VERSION = 1
PROFILE_METHOD = "duration-weighted rounded notes; core 5th-95th percentile"
PITCH_EXTRACTION_VERSION = "vocalscope-crepe-viterbi-pyin-notes-v1"
MIN_COMPARED_FRAMES = 50
NOTE_NAMES = ("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")


def midi_to_name(midi: int | float) -> str:
    value = int(round(float(midi)))
    return f"{NOTE_NAMES[value % 12]}{value // 12 - 1}"


def _clean_notes(notes: Iterable[Mapping[str, Any]]) -> list[dict[str, float]]:
    cleaned: list[dict[str, float]] = []
    for item in notes:
        try:
            midi = int(round(float(item["midi"])))
            duration = float(item["duration"])
            confidence = float(item.get("confidence", 1.0))
        except (KeyError, TypeError, ValueError):
            continue
        if not np.isfinite([midi, duration, confidence]).all() or duration <= 0:
            continue
        cleaned.append(
            {
                "midi": float(np.clip(midi, MIDI_MIN, MIDI_MAX)),
                "duration": duration,
                "confidence": float(np.clip(confidence, 0.0, 1.0)),
            }
        )
    if not cleaned:
        raise ValueError("보컬에서 음역 프로필에 사용할 유효한 음표를 찾지 못했습니다.")
    return cleaned


def weighted_percentile(notes: Iterable[Mapping[str, Any]], percentile: float) -> int:
    """Return a duration-weighted MIDI percentile using VocalScope semantics."""

    if not 0.0 <= percentile <= 1.0:
        raise ValueError("percentile must be between zero and one")
    cleaned = _clean_notes(notes)
    ordered = sorted(cleaned, key=lambda item: item["midi"])
    total = sum(item["duration"] for item in ordered)
    target = total * percentile
    cursor = 0.0
    for item in ordered:
        cursor += item["duration"]
        if cursor >= target:
            return int(item["midi"])
    return int(ordered[-1]["midi"])


def summarize_pitch_profile(
    notes: Iterable[Mapping[str, Any]],
    *,
    profile_kind: str,
    f0_agreement_ratio: float | None = None,
    octave_mismatch_ratio: float | None = None,
    compared_frames: int | None = None,
) -> dict[str, Any]:
    """Build a compact semitone distribution without retaining audio or frames."""

    cleaned = _clean_notes(notes)
    low = min(int(item["midi"]) for item in cleaned)
    high = max(int(item["midi"]) for item in cleaned)
    core_low = weighted_percentile(cleaned, 0.05)
    median = weighted_percentile(cleaned, 0.50)
    core_high = weighted_percentile(cleaned, 0.95)
    total_duration = sum(item["duration"] for item in cleaned)
    weighted_confidence = sum(
        item["duration"] * item["confidence"] for item in cleaned
    ) / total_duration
    histogram = np.zeros(MIDI_MAX - MIDI_MIN + 1, dtype=np.float64)
    for item in cleaned:
        histogram[int(item["midi"]) - MIDI_MIN] += item["duration"]
    histogram /= histogram.sum()

    agreement = (
        None if f0_agreement_ratio is None else float(np.clip(f0_agreement_ratio, 0.0, 1.0))
    )
    mismatch = (
        None
        if octave_mismatch_ratio is None
        else float(np.clip(octave_mismatch_ratio, 0.0, 1.0))
    )
    frames = None if compared_frames is None else max(0, int(compared_frames))
    reliable = len(cleaned) >= 3 and total_duration >= 2.0 and weighted_confidence >= 0.45
    quality_reasons: list[str] = []
    if len(cleaned) < 3 or total_duration < 2.0:
        quality_reasons.append("insufficient_voiced_notes")
    if weighted_confidence < 0.45:
        quality_reasons.append("low_note_confidence")
    if agreement is not None and agreement < 0.60:
        reliable = False
        quality_reasons.append("low_crepe_pyin_agreement")
    if mismatch is not None and mismatch > 0.10:
        reliable = False
        quality_reasons.append("high_octave_mismatch")
    if frames is not None and frames < MIN_COMPARED_FRAMES:
        reliable = False
        quality_reasons.append("insufficient_cross_model_frames")

    return {
        "schemaVersion": PROFILE_SCHEMA_VERSION,
        "profileKind": profile_kind,
        "method": PROFILE_METHOD,
        "histogramMidiMin": MIDI_MIN,
        "histogramMidiMax": MIDI_MAX,
        "histogram": [round(float(value), 8) for value in histogram],
        "lowestMidi": low,
        "highestMidi": high,
        "coreLowMidi": core_low,
        "medianMidi": median,
        "coreHighMidi": core_high,
        "lowest": midi_to_name(low),
        "highest": midi_to_name(high),
        "coreRange": f"{midi_to_name(core_low)}–{midi_to_name(core_high)}",
        "noteCount": len(cleaned),
        "voicedSeconds": round(total_duration, 2),
        "confidence": round(weighted_confidence, 4),
        "f0AgreementRatio": None if agreement is None else round(agreement, 4),
        "octaveMismatchRatio": None if mismatch is None else round(mismatch, 4),
        "comparedFrames": frames,
        "reliable": reliable,
        "qualityReasons": quality_reasons,
    }
