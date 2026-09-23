"""Catalog loading and deterministic vocal-range matching."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import time
from typing import Any, Mapping

import numpy as np

from pitch_profile import (
    MIDI_MAX,
    MIDI_MIN,
    PITCH_EXTRACTION_VERSION,
    PROFILE_SCHEMA_VERSION,
    midi_to_name,
)


RANGE_CATALOG_FILENAME = "range_profiles.json"
RANGE_SCORING_VERSION = "vocal-range-keyshift-first-98-v3"
MODE_WEIGHTS = {
    "timbre": {"timbre": 0.75, "range": 0.25},
    "range": {"timbre": 0.30, "range": 0.70},
}
KEY_SHIFT_MIN = -3
KEY_SHIFT_MAX = 3
KEY_SHIFT_BOUNDS = (KEY_SHIFT_MIN, KEY_SHIFT_MAX)
KEY_SHIFTS = tuple(range(KEY_SHIFT_MIN, KEY_SHIFT_MAX + 1))
COMFORT_PADDING_SEMITONES = 2
COVERAGE_CENTER = 0.80
COVERAGE_SIGMOID_SCALE = 0.05
CORE_EXCESS_DECAY_SEMITONES = 4.0
BASE_RANGE_SCORE_WEIGHT = 0.70
SOFT_FIT_SCORE_WEIGHT = 0.30
CENTER_SCORE_WEIGHT = 0.20
# 추천 후보 게이트: 최적 키에서도 회원의 (여유를 포함한) 음역 밖에 있는
# 시간 가중 음정 질량이 이 비율을 넘으면 '부를 수 있는 곡'으로 내지 않는다.
MAX_OUTSIDE_RANGE_RATIO = 0.05
# 앱 음역 측정(guided-range)으로 만든 프로파일. median 이 (하한+상한)//2 라 "주로 부르는 높이"가 아니다.
# 이 종류에는 중심음 맞추기(center_score)를 쓰지 않고, 98% 커버리지를 처음 만족하는 키를 고른다.
GUIDED_RANGE_PROFILE_KIND = "guided_range_measurement"
GUIDED_TARGET_COVERAGE = 0.98
KEY_SHIFT_POLICY_CENTER = "center-match-v2"
KEY_SHIFT_POLICY_GUIDED = "guided-first-98-v2"


def _is_guided_range(profile: Mapping[str, Any]) -> bool:
    return str(profile.get("profileKind", "")) == GUIDED_RANGE_PROFILE_KIND


def canonical_json_sha256(path: Path) -> str:
    """Hash JSON content independently of checkout line-ending conversion."""

    payload = json.loads(path.read_text(encoding="utf-8"))
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _validated_profile(profile: Mapping[str, Any], *, label: str) -> dict[str, Any]:
    if int(profile.get("schemaVersion", -1)) != PROFILE_SCHEMA_VERSION:
        raise ValueError(f"{label}: unsupported pitch profile schema")
    histogram = np.asarray(profile.get("histogram"), dtype=np.float64)
    expected = MIDI_MAX - MIDI_MIN + 1
    if histogram.shape != (expected,) or not np.all(np.isfinite(histogram)):
        raise ValueError(f"{label}: histogram must contain {expected} finite values")
    if np.any(histogram < 0) or float(histogram.sum()) <= 0:
        raise ValueError(f"{label}: histogram must be non-negative with positive mass")
    normalized = histogram / histogram.sum()
    result = dict(profile)
    result["histogram"] = normalized.tolist()
    for key in ("coreLowMidi", "medianMidi", "coreHighMidi"):
        value = int(result[key])
        if not MIDI_MIN <= value <= MIDI_MAX:
            raise ValueError(f"{label}: {key} is outside the supported MIDI range")
        result[key] = value
    if not result["coreLowMidi"] <= result["medianMidi"] <= result["coreHighMidi"]:
        raise ValueError(f"{label}: pitch percentiles are not ordered")
    result["reliable"] = bool(result.get("reliable", True))
    return result


@dataclass(frozen=True)
class RangeCatalog:
    root: Path
    metadata: dict[str, Any]
    profiles: dict[str, dict[str, Any]]

    @classmethod
    def load(
        cls,
        root: Path | str,
        *,
        expected_track_ids: set[str] | None = None,
        verify_hashes: bool = False,
        timings: dict[str, float] | None = None,
    ) -> "RangeCatalog":
        load_started = time.perf_counter()
        hash_verify_seconds = 0.0
        directory = Path(root).resolve()
        path = directory / RANGE_CATALOG_FILENAME
        if not path.is_file():
            raise FileNotFoundError(f"range catalog not found: {path}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        if int(payload.get("schemaVersion", -1)) != 1:
            raise ValueError("unsupported range catalog schema")
        if payload.get("scoringVersion") != RANGE_SCORING_VERSION:
            raise ValueError("unsupported range catalog scoring version")
        if payload.get("pitchExtractionVersion") != PITCH_EXTRACTION_VERSION:
            raise ValueError("unsupported range catalog pitch extraction version")
        if payload.get("keyShiftRange") != list(KEY_SHIFT_BOUNDS):
            raise ValueError(
                "unsupported range catalog key shift range: "
                f"expected {list(KEY_SHIFT_BOUNDS)}"
            )
        profiles = {
            str(track_id): _validated_profile(profile, label=str(track_id))
            for track_id, profile in payload.get("profiles", {}).items()
        }
        if not profiles:
            raise ValueError("range catalog contains no profiles")
        if expected_track_ids is not None and set(profiles) != set(expected_track_ids):
            raise ValueError(
                "range/timbre catalog track mismatch: "
                f"missing={sorted(set(expected_track_ids) - set(profiles))}, "
                f"extra={sorted(set(profiles) - set(expected_track_ids))}"
            )
        source_file = payload.get("source", {}).get("indexFile")
        source_hash = payload.get("source", {}).get("indexCanonicalSha256")
        if verify_hashes:
            if not source_file or not source_hash:
                raise ValueError("range catalog is missing index provenance metadata")
            source_path = directory / str(source_file)
            if not source_path.is_file():
                raise FileNotFoundError(f"range catalog source index not found: {source_path}")
            verify_started = time.perf_counter()
            if canonical_json_sha256(source_path) != source_hash:
                raise RuntimeError(f"range catalog source hash mismatch: {source_path}")
            hash_verify_seconds = time.perf_counter() - verify_started
        if timings is not None:
            timings.update(
                totalMs=(time.perf_counter() - load_started) * 1000,
                hashVerifyMs=hash_verify_seconds * 1000,
            )
        return cls(directory, payload, profiles)


def _shifted_histogram(profile: Mapping[str, Any], shift: int) -> tuple[np.ndarray, np.ndarray]:
    mass = np.asarray(profile["histogram"], dtype=np.float64)
    midi = np.arange(MIDI_MIN, MIDI_MAX + 1, dtype=np.float64) + shift
    return midi, mass / mass.sum()


def _coverage_fit(coverage: float) -> float:
    """Map coverage continuously around the former 80% eligibility boundary."""

    scaled = np.clip(
        (float(coverage) - COVERAGE_CENTER) / COVERAGE_SIGMOID_SCALE,
        -60.0,
        60.0,
    )
    return float(1.0 / (1.0 + np.exp(-scaled)))


def _score_at_shift(
    query: Mapping[str, Any], candidate: Mapping[str, Any], shift: int
) -> dict[str, Any]:
    observed_low = int(query["coreLowMidi"]) - COMFORT_PADDING_SEMITONES
    observed_high = int(query["coreHighMidi"]) + COMFORT_PADDING_SEMITONES
    midi, mass = _shifted_histogram(candidate, shift)
    inside = (midi >= observed_low) & (midi <= observed_high)
    coverage = float(mass[inside].sum())
    center_distance = abs(float(candidate["medianMidi"]) + shift - float(query["medianMidi"]))
    center_score = float(np.exp(-center_distance / 3.0))
    user_span = observed_high - observed_low
    song_span = int(candidate["coreHighMidi"]) - int(candidate["coreLowMidi"])
    span_excess = max(0, song_span - user_span)
    span_fit = float(np.exp(-span_excess / 4.0))
    center_weight = 0.0 if _is_guided_range(query) else CENTER_SCORE_WEIGHT
    base_score = (0.65 + CENTER_SCORE_WEIGHT - center_weight) * coverage + center_weight * center_score + 0.15 * span_fit
    shifted_core_low = int(candidate["coreLowMidi"]) + shift
    shifted_core_high = int(candidate["coreHighMidi"]) + shift
    low_excess = max(0.0, float(observed_low - shifted_core_low))
    high_excess = max(0.0, float(shifted_core_high - observed_high))
    total_core_excess = low_excess + high_excess
    coverage_fit = _coverage_fit(coverage)
    boundary_fit = float(np.exp(-total_core_excess / CORE_EXCESS_DECAY_SEMITONES))
    soft_fit = coverage_fit * boundary_fit
    score = BASE_RANGE_SCORE_WEIGHT * base_score + SOFT_FIT_SCORE_WEIGHT * soft_fit
    return {
        "score": float(np.clip(score, 0.0, 1.0)),
        "baseScore": float(np.clip(base_score, 0.0, 1.0)),
        "coverage": coverage,
        "coverageFit": coverage_fit,
        "boundaryFit": boundary_fit,
        "softFit": soft_fit,
        "centerScore": center_score,
        "spanFit": span_fit,
        "centerDistanceSemitones": center_distance,
        "keyShift": shift,
        "lowCoreExcessSemitones": low_excess,
        "highCoreExcessSemitones": high_excess,
        "totalCoreExcessSemitones": total_core_excess,
        "paddedObservedLowMidi": observed_low,
        "paddedObservedHighMidi": observed_high,
    }


def score_range_fit(
    query_profile: Mapping[str, Any], candidate_profile: Mapping[str, Any]
) -> dict[str, Any]:
    """Choose the best playable integer song transposition from -3 through +3 semitones."""

    query = _validated_profile(query_profile, label="query")
    candidate = _validated_profile(candidate_profile, label="candidate")
    options = [_score_at_shift(query, candidate, shift) for shift in KEY_SHIFTS]
    guided = _is_guided_range(query)
    if guided:
        # 원키에서 가장 적게 움직여 98%를 처음 만족하는 키를 권한다. ±3 안에서
        # 불가능하면 core 경계 초과가 가장 작은 끝 키까지 이동하도록 한다.
        covered = [item for item in options if item["coverage"] >= GUIDED_TARGET_COVERAGE]
        if covered:
            best = sorted(
                covered,
                key=lambda item: (abs(item["keyShift"]), -item["coverage"], -item["score"], item["keyShift"]),
            )[0]
        else:
            best = sorted(
                options,
                key=lambda item: (
                    item["totalCoreExcessSemitones"],
                    -item["coverage"],
                    -item["score"],
                    abs(item["keyShift"]),
                    item["keyShift"],
                ),
            )[0]
    else:
        best = sorted(
            options,
            key=lambda item: (
                -item["score"],
                -item["softFit"],
                -item["coverage"],
                abs(item["keyShift"]),
                item["keyShift"],
            ),
        )[0]
    shift = int(best["keyShift"])
    original_low = int(candidate["coreLowMidi"])
    original_high = int(candidate["coreHighMidi"])
    result = dict(best)
    result.update(
        {
            "scoringVersion": RANGE_SCORING_VERSION,
            "keyShiftPolicy": KEY_SHIFT_POLICY_GUIDED if guided else KEY_SHIFT_POLICY_CENTER,
            "score": round(float(best["score"]), 6),
            "baseScore": round(float(best["baseScore"]), 6),
            "coverage": round(float(best["coverage"]), 6),
            "outsideRangeRatio": round(float(1.0 - best["coverage"]), 6),
            "coverageFit": round(float(best["coverageFit"]), 6),
            "boundaryFit": round(float(best["boundaryFit"]), 6),
            "softFit": round(float(best["softFit"]), 6),
            "centerScore": round(float(best["centerScore"]), 6),
            "spanFit": round(float(best["spanFit"]), 6),
            "centerDistanceSemitones": round(float(best["centerDistanceSemitones"]), 3),
            "lowCoreExcessSemitones": round(float(best["lowCoreExcessSemitones"]), 3),
            "highCoreExcessSemitones": round(float(best["highCoreExcessSemitones"]), 3),
            "totalCoreExcessSemitones": round(float(best["totalCoreExcessSemitones"]), 3),
            "songCoreLowMidi": original_low,
            "songCoreHighMidi": original_high,
            "songCoreRange": f"{midi_to_name(original_low)}–{midi_to_name(original_high)}",
            "shiftedCoreLowMidi": original_low + shift,
            "shiftedCoreHighMidi": original_high + shift,
            "shiftedCoreRange": (
                f"{midi_to_name(original_low + shift)}–{midi_to_name(original_high + shift)}"
            ),
            "paddedObservedRange": (
                f"{midi_to_name(best['paddedObservedLowMidi'])}–"
                f"{midi_to_name(best['paddedObservedHighMidi'])}"
            ),
            "catalogProfileReliable": bool(candidate.get("reliable", True)),
            "rangeContributionApplied": bool(candidate.get("reliable", True)),
        }
    )
    if not result["catalogProfileReliable"]:
        result["scoreBeforeReliabilityGate"] = result["score"]
        result["score"] = 0.0
        result["softFit"] = 0.0
        result["qualityReasons"] = list(
            candidate.get("qualityReasons") or ["unreliable_catalog_pitch_profile"]
        )
    return result
