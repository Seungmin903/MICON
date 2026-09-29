"""Song-duration-weighted measured vocal quality, used as a soft recommendation score.

Missing notes have no score. They reduce the contribution's confidence rather than
becoming measured zeroes or interpolated display values.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Any, Mapping

import numpy as np

from pitch_profile import MIDI_MIN, MIDI_MAX
from .pitch_range import KEY_SHIFTS, _score_at_shift

RECOMMENDATION_SCORING_VERSION = "voice-stability-70-15-15-v1"
STABILITY_SCORING_VERSION = "measured-stability-v1"
QUALITY_VERSION = "personal-acoustic-v1"
QUALITY_VERSIONS = frozenset({QUALITY_VERSION, "personal-acoustic-v2"})
ECAPA_WEIGHT = 0.70
SINGER_BYOL_WEIGHT = 0.15
STABILITY_WEIGHT = 0.15
TIMBRE_WEIGHT = ECAPA_WEIGHT + SINGER_BYOL_WEIGHT
MIN_CATALOG_CONFIDENCE = 0.55


@dataclass(frozen=True)
class StabilityCurve:
    scores: Mapping[int, float]

    @classmethod
    def from_payload(cls, payload: Any) -> StabilityCurve | None:
        if not isinstance(payload, Mapping) or payload.get("version") not in QUALITY_VERSIONS:
            return None
        points = payload.get("points")
        if not isinstance(points, list) or not points:
            return None
        scores = {}
        for point in points:
            if not isinstance(point, Mapping):
                return None
            midi, score = point.get("midi"), point.get("score")
            if (type(midi) is not int or not MIDI_MIN <= midi <= MIDI_MAX
                    or midi in scores or type(score) not in (int, float)
                    or not math.isfinite(score) or not 0 <= score <= 100):
                return None
            scores[midi] = float(score) / 100.0
        return cls(scores)


def stability_at_shift(curve: StabilityCurve, candidate: Mapping[str, Any], shift: int) -> dict[str, float] | None:
    if not candidate.get("reliable", True):
        return None
    confidence = float(candidate.get("confidence", 1.0))
    if not math.isfinite(confidence) or confidence < MIN_CATALOG_CONFIDENCE:
        return None
    mass = np.asarray(candidate["histogram"], dtype=float)
    if mass.ndim != 1 or not np.isfinite(mass).all() or (mass < 0).any() or mass.sum() <= 0:
        return None
    mass = mass / mass.sum()
    first_midi = int(candidate.get("histogramMidiMin", MIDI_MIN))
    measured = [(float(weight), curve.scores[first_midi + index + shift])
                for index, weight in enumerate(mass)
                if weight > 0 and first_midi + index + shift in curve.scores]
    coverage = sum(weight for weight, _ in measured)
    if coverage <= 0:
        return None
    score = min(1.0, max(0.0, sum(weight * value for weight, value in measured) / coverage))
    coverage = min(1.0, coverage)  # Floating-point summation must not invalidate a complete profile.
    return {"score": score, "measuredCoverage": coverage,
            "confidence": coverage * min(confidence, 1.0)}


def best_stability_fit(curve: StabilityCurve | None, candidate: Mapping[str, Any] | None,
                       user: Mapping[str, Any] | None = None) -> dict[str, Any] | None:
    if curve is None or candidate is None:
        return None
    options = []
    for shift in KEY_SHIFTS:
        fit = stability_at_shift(curve, candidate, shift)
        if fit is not None:
            # Unknown pitch mass is neutral for key selection, never measured zero.
            utility = fit["confidence"] * fit["score"] + (1 - fit["confidence"]) * 0.5
            options.append((utility, shift, fit))
    if not options:
        return None
    _, shift, best = min(options, key=lambda item: (
        -round(item[0], 9), -round(item[2]["confidence"], 9), abs(item[1]), item[1]))
    result = _score_at_shift(user, candidate, shift) if user is not None else {}
    result.update(best)
    result.update(scoringVersion=STABILITY_SCORING_VERSION, keyShift=shift,
                  songCoreLowMidi=int(candidate["coreLowMidi"]),
                  songCoreHighMidi=int(candidate["coreHighMidi"]),
                  catalogProfileReliable=True, rangeContributionApplied=True)
    return result


def blend_stability(timbre: float, fit: Mapping[str, Any] | None) -> tuple[float, dict[str, float]]:
    weight = STABILITY_WEIGHT * float(fit["confidence"]) if fit is not None else 0.0
    final = (1 - weight) * timbre + weight * float(fit["score"]) if fit is not None else timbre
    return float(final), {
        "ecapa": (1 - weight) * ECAPA_WEIGHT / TIMBRE_WEIGHT,
        "singer_byol": (1 - weight) * SINGER_BYOL_WEIGHT / TIMBRE_WEIGHT,
        "stability": weight,
    }
