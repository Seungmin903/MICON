"""Fast, conservative MR-leakage detection for TIMBRE recordings.

This is an energy gate, not a classifier.  It intentionally routes only
obvious accompaniment leakage to the slower vocal-separation path.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf


DETECTOR_VERSION = "mr-energy-v1"
FRAME_SECONDS = 0.5
HOP_SECONDS = 0.25
DEFAULT_ENERGY_FLOOR_RATIO = 0.30
DEFAULT_MAX_DYNAMIC_RANGE = 8.0


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, "") or default)
    except ValueError:
        return default


def detect_mr_suspected(audio_path: Path) -> dict[str, Any]:
    """Return a persisted, explainable decision from short-time RMS energy.

    The lower energy percentile represents windows that should be relatively
    quiet between sung phrases.  Clear speaker playback keeps that floor high
    and compresses the recording's dynamic range.  Both conditions are needed
    to avoid adding separation delay to ordinary headphone recordings.
    """

    signal, sample_rate = sf.read(audio_path, dtype="float32", always_2d=True)
    mono = np.mean(signal, axis=1, dtype=np.float32)
    frame_length = max(1, int(sample_rate * FRAME_SECONDS))
    hop_length = max(1, int(sample_rate * HOP_SECONDS))
    if len(mono) < frame_length:
        raise ValueError("MR 판정을 하려면 0.5초 이상의 음성이 필요합니다.")

    starts = range(0, len(mono) - frame_length + 1, hop_length)
    rms = np.fromiter(
        (float(np.sqrt(np.mean(np.square(mono[start : start + frame_length]), dtype=np.float64)))
         for start in starts),
        dtype=np.float64,
    )
    if not np.isfinite(rms).all() or not np.any(rms > 1e-7):
        raise ValueError("MR 판정에 사용할 수 있는 음량이 없습니다.")

    p10, p20, p80, p95 = (float(np.percentile(rms, percentile)) for percentile in (10, 20, 80, 95))
    floor_ratio = p20 / max(p80, 1e-7)
    dynamic_range = p95 / max(p10, 1e-7)
    floor_threshold = _env_float("MICON_MR_ENERGY_FLOOR_RATIO", DEFAULT_ENERGY_FLOOR_RATIO)
    dynamic_threshold = _env_float("MICON_MR_MAX_DYNAMIC_RANGE", DEFAULT_MAX_DYNAMIC_RANGE)
    quiet_windows_loud = floor_ratio >= floor_threshold
    dynamic_range_small = dynamic_range <= dynamic_threshold
    suspected = quiet_windows_loud and dynamic_range_small

    return {
        "detectorVersion": DETECTOR_VERSION,
        "status": "SUSPECTED" if suspected else "NOT_SUSPECTED",
        "displayLabel": "MR 의심" if suspected else None,
        "requiresSeparation": suspected,
        "reasonCodes": (["QUIET_WINDOWS_LOUD", "LOW_DYNAMIC_RANGE"] if suspected else []),
        "metrics": {
            "frameSeconds": FRAME_SECONDS,
            "frameCount": int(len(rms)),
            "lowEnergyFloorRatio": round(floor_ratio, 3),
            "dynamicRangeP95P10": round(dynamic_range, 2),
            "thresholds": {
                "lowEnergyFloorRatio": floor_threshold,
                "dynamicRangeP95P10": dynamic_threshold,
            },
        },
    }
