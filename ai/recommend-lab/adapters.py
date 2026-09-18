"""Explicit replay input adapters. Never import the stateful production API."""
from __future__ import annotations

import numpy as np

DIMENSIONS = {"ecapa": 192, "singer_byol": 1000}


def matrices(values):
    out = {}
    for key, dim in DIMENSIONS.items():
        value = values.get(key)
        if isinstance(value, dict):
            value = (value.get("segmentInfo") or {}).get("embeddings") or value.get("embedding")
        x = np.asarray(value, dtype=np.float64)
        if x.ndim == 1:
            x = x.reshape(1, -1)
        if x.ndim != 2 or x.shape[1] != dim or not 1 <= len(x) <= 12 or not np.isfinite(x).all():
            raise ValueError(f"{key}: 1~12개 구간의 {dim}차원 유한 벡터가 필요합니다.")
        norms = np.linalg.norm(x, axis=1)
        if not np.isfinite(norms).all() or np.any(norms < 1e-12):
            raise ValueError(f"{key}: 영벡터 또는 과도한 값입니다.")
        out[key] = x / norms[:, None]
    return out


def profile_from_range(callback):
    """Analysis replay only: successful RANGE notes, not guessed by temporal proximity.

    Matches production's stableLow/High + pitchDistribution normalization for
    this subset. The true recommendation voiceProfile/preferences must come
    from an explicitly saved case to claim identical-request replay.
    """
    if callback.get("status") != "COMPLETED":
        return None
    low, high = callback.get("lowMidi"), callback.get("highMidi")
    if low is None or high is None or high - low < 1:
        return None
    mass = np.zeros(60)
    for note in callback.get("noteResults", []):
        midi = note.get("midi")
        if str(note.get("status", "")).lower() == "passed" and midi is not None and 36 <= midi <= 95:
            mass[int(round(midi)) - 36] += max(float(note.get("holdMs") or 0), 1.0)
    if mass.sum() <= 0:
        mass[int(low)-36:int(high)-36+1] = 1
    return {"schemaVersion": 1, "coreLowMidi": int(low), "coreHighMidi": int(high),
            "medianMidi": (int(low)+int(high))//2, "histogram": mass.tolist(), "reliable": True}

