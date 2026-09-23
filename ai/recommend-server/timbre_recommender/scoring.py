"""Two-stage ECAPA + Singer-BYOL retrieval and continuous late fusion."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any, Iterable, Mapping

import numpy as np

from .catalog import CatalogIndex, Track
from .contracts import MODEL_CONTRACTS, MODEL_KEYS, SCORING_VERSION
from .pitch_range import (
    KEY_SHIFT_BOUNDS,
    MODE_WEIGHTS,
    PITCH_EXTRACTION_VERSION,
    RANGE_SCORING_VERSION,
    score_range_fit,
)


@dataclass(frozen=True)
class RankerConfig:
    candidate_k_per_model: int = 50
    top_k: int = 10
    centroid_weight: float = 0.30
    segment_weight: float = 0.70
    base_ecapa_weight: float = 0.50
    minimum_stable_votes: int = 5
    max_query_segments: int = 12

    def validate(self) -> None:
        if self.candidate_k_per_model <= 0 or self.top_k <= 0:
            raise ValueError("candidate_k_per_model and top_k must be positive")
        if self.max_query_segments <= 0 or self.minimum_stable_votes <= 0:
            raise ValueError("segment limits must be positive")
        if not np.isclose(self.centroid_weight + self.segment_weight, 1.0):
            raise ValueError("centroid_weight + segment_weight must equal 1")
        if not 0.0 <= self.base_ecapa_weight <= 1.0:
            raise ValueError("base_ecapa_weight must be in [0, 1]")


def _normalize_rows(values: np.ndarray, model_key: str) -> np.ndarray:
    contract = MODEL_CONTRACTS[model_key]
    array = np.asarray(values, dtype=np.float32)
    if array.ndim == 1:
        array = array.reshape(1, -1)
    if array.ndim != 2 or array.shape[1] != contract.dimension:
        raise ValueError(
            f"query {model_key} shape must be (segments, {contract.dimension}), "
            f"got {array.shape}"
        )
    if len(array) == 0 or not np.all(np.isfinite(array)):
        raise ValueError(f"query {model_key} must be non-empty and finite")
    norms = np.linalg.norm(array, axis=1, keepdims=True)
    if np.any(norms <= 1e-12):
        raise ValueError(f"query {model_key} contains a zero-norm segment")
    return np.ascontiguousarray(array / norms, dtype=np.float32)


def _uniformly_cap(values: np.ndarray, maximum: int) -> np.ndarray:
    if len(values) <= maximum:
        return values
    indices = np.linspace(0, len(values) - 1, num=maximum, dtype=np.int64)
    return np.ascontiguousarray(values[indices])


def _centroid(values: np.ndarray) -> np.ndarray:
    result = values.mean(axis=0)
    norm = float(np.linalg.norm(result))
    if norm <= 1e-12:
        raise ValueError("embedding centroid has zero norm")
    return np.asarray(result / norm, dtype=np.float32)


def _model_track_score(
    query: np.ndarray,
    candidate: np.ndarray,
    *,
    centroid_weight: float,
    segment_weight: float,
) -> dict[str, float]:
    query_centroid = _centroid(query)
    candidate_centroid = _centroid(candidate)
    centroid_similarity = float(query_centroid @ candidate_centroid)
    similarities = query @ candidate.T
    query_best = np.max(similarities, axis=1)
    candidate_best = np.max(similarities, axis=0)
    query_coverage = float(np.median(query_best))
    candidate_coverage = float(np.median(candidate_best))
    segment_coverage = 0.5 * (query_coverage + candidate_coverage)
    raw_score = centroid_weight * centroid_similarity + segment_weight * segment_coverage
    consistency = float(1.0 - np.clip(np.std(query_best), 0.0, 1.0))
    return {
        "raw_score": raw_score,
        "centroid_similarity": centroid_similarity,
        "segment_coverage": segment_coverage,
        "query_coverage": query_coverage,
        "candidate_coverage": candidate_coverage,
        "match_consistency": consistency,
    }


def _absolute_similarity(raw_score: float) -> float:
    """Keep cosine-derived similarity independent of the current candidate set."""

    return float(np.clip(raw_score, 0.0, 1.0))


def _retrieval_diagnostics(
    query: np.ndarray,
    tracks: tuple[Track, ...],
    centroids: np.ndarray,
    centroid_scores: np.ndarray,
    *,
    model_key: str,
    minimum_stable_votes: int,
) -> dict[str, Any]:
    order = sorted(
        range(len(tracks)),
        key=lambda index: (-float(centroid_scores[index]), tracks[index].track_id),
    )
    margin = (
        float(centroid_scores[order[0]] - centroid_scores[order[1]])
        if len(order) >= 2
        else 0.0
    )
    segment_scores = query @ centroids.T
    votes = Counter(tracks[int(index)].track_id for index in np.argmax(segment_scores, axis=1))
    stable_track_id, stable_votes = sorted(
        votes.items(), key=lambda item: (-item[1], item[0])
    )[0]
    threshold = MODEL_CONTRACTS[model_key].margin_threshold
    margin_high = margin >= threshold
    stability_high = len(query) >= minimum_stable_votes and stable_votes >= minimum_stable_votes
    return {
        "top1_track_id": tracks[order[0]].track_id,
        "top1_margin": margin,
        "margin_threshold": threshold,
        "margin_high": margin_high,
        "stable_track_id": stable_track_id,
        "stable_top1_votes": stable_votes,
        "query_segment_count": len(query),
        "minimum_stable_votes": minimum_stable_votes,
        "stability_high": stability_high,
        "high_confidence": bool(margin_high or stability_high),
    }


def recommend(
    catalog: CatalogIndex,
    query_embeddings: dict[str, np.ndarray],
    *,
    config: RankerConfig | None = None,
    excluded_track_ids: Iterable[str] = (),
    query_pitch_profile: Mapping[str, Any] | None = None,
    catalog_pitch_profiles: Mapping[str, Mapping[str, Any]] | None = None,
    mode: str = "timbre",
) -> dict[str, Any]:
    """Recommend tracks with timbre-first or observed-range-first fusion.

    Fast centroid search produces ECAPA/BYOL candidate unions. Precise segment
    matching keeps its absolute cosine-derived score per model and uses fixed
    model weights so a pair's score does not depend on the candidate set. When compatible
    pitch profiles are supplied, a -3..+3 semitone range match is added before
    the final ranking.
    """

    config = config or RankerConfig()
    config.validate()
    if mode not in MODE_WEIGHTS:
        raise ValueError(f"unsupported recommendation mode: {mode}")
    missing = set(MODEL_KEYS) - query_embeddings.keys()
    if missing:
        raise ValueError(f"query is missing model embeddings: {sorted(missing)}")
    queries = {
        key: _uniformly_cap(
            _normalize_rows(query_embeddings[key], key), config.max_query_segments
        )
        for key in MODEL_KEYS
    }
    tracks = catalog.select(excluded_track_ids)
    if not tracks:
        raise ValueError("no catalog tracks remain after exclusions")

    range_requested = query_pitch_profile is not None and catalog_pitch_profiles is not None
    range_enabled = bool(range_requested and query_pitch_profile.get("reliable", True))
    range_fallback_reason: str | None = None
    if not range_requested:
        range_fallback_reason = "pitch_profiles_not_supplied"
    elif not query_pitch_profile.get("reliable", True):
        reasons = query_pitch_profile.get("qualityReasons") or ["unreliable_pitch_profile"]
        range_fallback_reason = ",".join(str(reason) for reason in reasons)
    all_range_matches: dict[str, dict[str, Any]] = {}
    if range_enabled:
        missing_ranges = [
            track.track_id for track in tracks if track.track_id not in catalog_pitch_profiles
        ]
        if missing_ranges:
            raise ValueError(f"catalog range profiles are missing tracks: {missing_ranges}")
        all_range_matches = {
            track.track_id: score_range_fit(
                query_pitch_profile, catalog_pitch_profiles[track.track_id]
            )
            for track in tracks
        }

    centroids: dict[str, np.ndarray] = {}
    fast_scores: dict[str, np.ndarray] = {}
    diagnostics: dict[str, dict[str, Any]] = {}
    candidate_ids: set[str] = set()
    for model_key in MODEL_KEYS:
        model_centroids = np.stack(
            [_centroid(catalog.embeddings(track, model_key)) for track in tracks]
        )
        scores = model_centroids @ _centroid(queries[model_key])
        centroids[model_key] = model_centroids
        fast_scores[model_key] = scores
        diagnostics[model_key] = _retrieval_diagnostics(
            queries[model_key],
            tracks,
            model_centroids,
            scores,
            model_key=model_key,
            minimum_stable_votes=config.minimum_stable_votes,
        )
        order = sorted(
            range(len(tracks)),
            key=lambda index: (-float(scores[index]), tracks[index].track_id),
        )
        for index in order[: config.candidate_k_per_model]:
            candidate_ids.add(tracks[index].track_id)

    if range_enabled:
        range_order = sorted(
            tracks,
            key=lambda track: (
                -all_range_matches[track.track_id]["score"],
                abs(all_range_matches[track.track_id]["keyShift"]),
                track.track_id,
            ),
        )
        range_candidate_limit = max(config.candidate_k_per_model, config.top_k)
        for track in range_order[:range_candidate_limit]:
            candidate_ids.add(track.track_id)

    weights = {
        "ecapa": config.base_ecapa_weight,
        "singer_byol": 1.0 - config.base_ecapa_weight,
    }
    candidates = tuple(track for track in tracks if track.track_id in candidate_ids)
    details: dict[str, dict[str, Any]] = {
        track.track_id: {
            "track_id": track.track_id,
            "title": track.title,
            "artist": track.artist,
            "metadata": track.metadata,
            "models": {},
        }
        for track in candidates
    }

    for model_key in MODEL_KEYS:
        for track in candidates:
            score = _model_track_score(
                queries[model_key],
                catalog.embeddings(track, model_key),
                centroid_weight=config.centroid_weight,
                segment_weight=config.segment_weight,
            )
            score["absolute_score"] = _absolute_similarity(score["raw_score"])
            details[track.track_id]["models"][model_key] = score

    feature_weights = MODE_WEIGHTS[mode] if range_enabled else {"timbre": 1.0, "range": 0.0}
    for item in details.values():
        timbre_score = float(
            sum(
                weights[key] * item["models"][key]["absolute_score"]
                for key in MODEL_KEYS
            )
        )
        item["timbre_score"] = timbre_score
        if range_enabled:
            range_fit = all_range_matches[item["track_id"]]
            item["range_fit"] = range_fit
            fusion_score = float(
                feature_weights["timbre"] * timbre_score
                + feature_weights["range"] * range_fit["score"]
            )
            item["fusion_score"] = fusion_score
            item["final_score"] = fusion_score
        else:
            item["range_fit"] = None
            item["fusion_score"] = timbre_score
            item["final_score"] = timbre_score

    ranked = sorted(
        details.values(),
        key=lambda item: (
            -item["final_score"],
            -sum(item["models"][key]["raw_score"] for key in MODEL_KEYS),
            item["track_id"],
        ),
    )
    for rank, item in enumerate(ranked, start=1):
        item["rank"] = rank

    public_pitch_profile = None
    if query_pitch_profile is not None:
        public_pitch_profile = {
            key: value
            for key, value in query_pitch_profile.items()
            if key not in {"histogram", "sourceSha256"}
        }
    return {
        "algorithm": {
            "scoring_version": SCORING_VERSION,
            "range_scoring_version": RANGE_SCORING_VERSION if range_enabled else None,
            "pitch_extraction_version": (
                PITCH_EXTRACTION_VERSION if query_pitch_profile is not None else None
            ),
            "scope": (
                "vocal_timbre_and_observed_range" if range_enabled else "vocal_timbre_only"
            ),
            "requested_mode": mode,
            "applied_mode": mode if range_enabled else "timbre_only",
            "feature_weights": feature_weights,
            "ranking_score": "continuous weighted fusion",
            "candidate_strategy": (
                f"top_{config.candidate_k_per_model}_per_feature_union"
                if range_enabled
                else f"top_{config.candidate_k_per_model}_per_model_union"
            ),
            "model_score": {
                "centroid_weight": config.centroid_weight,
                "symmetric_segment_coverage_weight": config.segment_weight,
            },
            "normalization": "absolute cosine-derived similarity clipped to [0, 1]",
            "score_comparability": (
                "pairwise model scores are independent of candidate-set membership; "
                "final ranks still depend on which tracks are available"
            ),
            "base_weights": {
                "ecapa": config.base_ecapa_weight,
                "singer_byol": 1.0 - config.base_ecapa_weight,
            },
            "effective_weights": weights,
            "weight_reason": "fixed_candidate_independent_weights",
            "confidence_rule_status": "diagnostic_only_not_used_for_weighting",
            "range_scoring": {
                "enabled": range_enabled,
                "fallback_reason": range_fallback_reason,
                "key_shift_semitones": list(KEY_SHIFT_BOUNDS),
                "observed_range_padding_semitones": 2,
                "coverage_sigmoid_center": 0.80,
                "coverage_sigmoid_scale": 0.05,
                "core_excess_decay_semitones": 4.0,
                "base_score_weight": 0.70,
                "soft_fit_weight": 0.30,
            },
        },
        "query": {
            "segments": {key: len(queries[key]) for key in MODEL_KEYS},
            "pitchProfile": public_pitch_profile,
        },
        "model_diagnostics": diagnostics,
        "catalog_track_count": len(tracks),
        "candidate_union_count": len(candidates),
        "limitations": [
            (
                "The uploaded clip measures only the observed sung range, not the user's "
                "physiological or fully comfortable range."
            ),
            "Range fit uses the robust 5th-95th percentile and allows song transposition from -3 through +3 semitones.",
            "Absolute cosine-derived similarity is a stable pairwise score, not a calibrated success probability.",
            "Genre, mood, likes, and accumulated user profile are not used.",
        ],
        "recommendations": ranked[: config.top_k],
    }
