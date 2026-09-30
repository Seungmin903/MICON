"""Stable model and index contracts used by the local recommender."""

from __future__ import annotations

from dataclasses import dataclass


INDEX_SCHEMA_VERSION = 1
SCORING_VERSION = "ecapa-singer-byol-absolute-fusion-v3"
ARTIST_SEARCH_SCORING_VERSION = "ecapa80-singer-byol20-absolute-fusion-v1"


@dataclass(frozen=True)
class ModelContract:
    key: str
    display_name: str
    dimension: int
    input_sample_rate: int
    window_seconds: float
    preprocessing: str
    margin_threshold: float


MODEL_CONTRACTS = {
    "ecapa": ModelContract(
        key="ecapa",
        display_name="ECAPA-TDNN VoxCeleb",
        dimension=192,
        input_sample_rate=16_000,
        window_seconds=4.0,
        preprocessing="mono 4 s -> per-window RMS 0.1 -> 16 kHz -> L2",
        margin_threshold=0.060,
    ),
    "singer_byol": ModelContract(
        key="singer_byol",
        display_name="Singer-BYOL",
        dimension=1_000,
        input_sample_rate=44_100,
        window_seconds=4.0,
        preprocessing="mono 4 s at 44.1 kHz -> per-window peak normalize -> L2",
        margin_threshold=0.079,
    ),
}

MODEL_KEYS = tuple(MODEL_CONTRACTS)
