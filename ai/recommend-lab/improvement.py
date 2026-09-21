"""Read-only evaluation of the proposed recommendation-policy changes.

This lab-only reranker consumes already-scored catalog rows and never changes the
production scorer, index, jobs, or recommendation records.
"""
from __future__ import annotations

from collections import Counter
from math import ceil
from typing import Any, Mapping, Sequence

import numpy as np


OUTSIDE_MASS_LIMIT = 0.05
CONSENSUS_FRACTIONS = (0.23, 0.35)
RRF_K = 60
MAX_SONGS_PER_ARTIST = 2


def _outside_mass_ratio(row: Mapping[str, Any], profile: Mapping[str, Any] | None,
                        ranges: Mapping[str, Mapping[str, Any]]) -> float | None:
    """Duration-weighted catalog mass outside the user's core range.

    Profiles retain histograms, not contiguous timestamps: this is a testable
    proxy for the proposal's time-ratio gate, not a claim about one hard phrase.
    """
    if not profile or not row.get("range_fit"):
        return None
    song = ranges.get(str(row["track_id"]))
    if not song:
        return None
    mass = np.asarray(song.get("histogram"), dtype=float)
    if mass.ndim != 1 or len(mass) == 0 or not np.all(np.isfinite(mass)) or mass.sum() <= 0:
        return None
    low, high = int(profile["coreLowMidi"]), int(profile["coreHighMidi"])
    shift = int(row["range_fit"]["keyShift"])
    midi = np.arange(len(mass)) + int(song.get("histogramMidiMin", 36)) + shift
    return float(mass[(midi < low) | (midi > high)].sum() / mass.sum())


def _artist_summary(items: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    counts = Counter(str(item["artist"]) for item in items)
    repeated = sorted(
        ({"artist": artist, "count": count} for artist, count in counts.items() if count > 1),
        key=lambda item: (-item["count"], item["artist"]),
    )
    return {"count": len(items), "uniqueArtistCount": len(counts),
            "duplicateItems": len(items) - len(counts),
            "maxSongsPerArtist": max(counts.values(), default=0), "repeatedArtists": repeated}


def _key_summary(items: Sequence[Mapping[str, Any]]) -> float | None:
    values = [abs(int(item["recommended_key"])) for item in items if item.get("recommended_key") is not None]
    return round(sum(values) / len(values), 3) if values else None


def _ranked(pool: Sequence[Mapping[str, Any]], axis: str) -> list[Mapping[str, Any]]:
    return sorted(pool, key=lambda row: (-float(row["models"][axis]["absolute_score"]), str(row["track_id"])))


def _public_pick(row: Mapping[str, Any], *, source: str, ranks: Mapping[str, int],
                 outside_mass_ratio: float | None) -> dict[str, Any]:
    fit = row.get("range_fit") or {}
    return {"track_id": row["track_id"], "title": row["title"], "artist": row["artist"],
            "final_score": round(float(row["final_score"]), 6),
            "recommended_key": int(fit["keyShift"]) if fit else None, "source": source,
            "ecapaRank": ranks["ecapa"], "singerByolRank": ranks["singer_byol"],
            "outsideMassRatio": None if outside_mass_ratio is None else round(outside_mass_ratio, 6)}


def evaluate(rows: Sequence[Mapping[str, Any]], *, baseline: Sequence[Mapping[str, Any]],
             profile: Mapping[str, Any] | None, range_available: bool,
             ranges: Mapping[str, Mapping[str, Any]], excluded_track_ids: set[str], top_k: int) -> dict[str, Any]:
    """Compare production selections with the fixed proposed-policy ladder."""
    outside = {str(row["track_id"]): _outside_mass_ratio(row, profile, ranges) for row in rows}
    row_by_id = {str(row["track_id"]): row for row in rows}
    pool, excluded_counts = [], Counter()
    for row in rows:
        track_id = str(row["track_id"])
        if track_id in excluded_track_ids:
            excluded_counts["source"] += 1
        elif row.get("is_rap"):
            excluded_counts["rap"] += 1
        elif range_available and not (ranges.get(track_id) or {}).get("reliable", True):
            excluded_counts["unreliable_profile"] += 1
        elif range_available and (outside[track_id] is None or outside[track_id] > OUTSIDE_MASS_LIMIT):
            excluded_counts["outside_mass"] += 1
        else:
            pool.append(row)

    orders = {axis: _ranked(pool, axis) for axis in ("ecapa", "singer_byol")}
    ranks = {axis: {str(row["track_id"]): index for index, row in enumerate(order, start=1)}
             for axis, order in orders.items()}
    selected, used, artist_counts = [], set(), Counter()

    def consensus(limit: int) -> list[Mapping[str, Any]]:
        return sorted(
            (row for row in pool if max(ranks[axis][str(row["track_id"])] for axis in ranks) <= limit),
            key=lambda row: (sum(ranks[axis][str(row["track_id"])] for axis in ranks),
                             -float(row["final_score"]),
                             abs(int((row.get("range_fit") or {}).get("keyShift", 0))), str(row["track_id"])),
        )

    first_limit, second_limit = (max(1, ceil(len(pool) * fraction)) for fraction in CONSENSUS_FRACTIONS)
    rrf = sorted(pool, key=lambda row: (
        -sum(1.0 / (RRF_K + ranks[axis][str(row["track_id"])]) for axis in ranks),
        abs(int((row.get("range_fit") or {}).get("keyShift", 0))), -float(row["final_score"]), str(row["track_id"]),
    ))
    stages = ((consensus(first_limit), "두 모델 상위 23% 합의"),
              (consensus(second_limit), "두 모델 상위 35% 합의"), (rrf, "RRF 보충"))

    def take(*, only_new_artist: bool) -> None:
        for candidates, source in stages:
            for row in candidates:
                if len(selected) >= top_k:
                    return
                track_id, artist = str(row["track_id"]), str(row["artist"])
                if (track_id in used or artist_counts[artist] >= MAX_SONGS_PER_ARTIST
                        or (only_new_artist and artist_counts[artist] > 0)):
                    continue
                used.add(track_id); artist_counts[artist] += 1
                selected.append(_public_pick(row, source=source,
                                             ranks={axis: ranks[axis][track_id] for axis in ranks},
                                             outside_mass_ratio=outside[track_id]))

    # An upper bound alone can still introduce a duplicate where production had none.
    # Take a first pass of distinct artists; only then use the second-song allowance.
    take(only_new_artist=True)
    take(only_new_artist=False)

    baseline_public = []
    for item in baseline:
        track_id = str(item["track_id"])
        baseline_public.append({"track_id": track_id, "title": item["title"], "artist": item["artist"],
                                "recommended_key": item.get("recommended_key"), "outsideMassRatio": outside.get(track_id)})
    baseline_metrics, candidate_metrics = _artist_summary(baseline_public), _artist_summary(selected)
    baseline_metrics["meanAbsoluteKeyShift"], candidate_metrics["meanAbsoluteKeyShift"] = _key_summary(baseline_public), _key_summary(selected)
    if range_available:
        for items, metrics in ((baseline_public, baseline_metrics), (selected, candidate_metrics)):
            metrics["outsideMassPassingCount"] = sum(item["outsideMassRatio"] is not None and item["outsideMassRatio"] <= OUTSIDE_MASS_LIMIT for item in items)
    diversity_improved = (candidate_metrics["duplicateItems"] < baseline_metrics["duplicateItems"]
                           and candidate_metrics["maxSongsPerArtist"] <= MAX_SONGS_PER_ARTIST)
    return {
        "rules": {"outsideMassLimit": OUTSIDE_MASS_LIMIT, "consensusFractions": list(CONSENSUS_FRACTIONS),
                  "rrfK": RRF_K, "maxSongsPerArtist": MAX_SONGS_PER_ARTIST,
                  "keyRule": "동일한 합의/RRF 순위에서는 키 이동이 작은 곡을 우선"},
        "rangeProxy": {"available": range_available,
                       "description": "곡의 duration-weighted 음정 히스토그램 질량입니다. 연속된 어려운 한 구절이나 발성 난이도를 측정하지는 않습니다."},
        "candidatePoolCount": len(pool), "excluded": dict(sorted(excluded_counts.items())),
        "baseline": {"recommendations": baseline_public, "metrics": baseline_metrics},
        "candidate": {"recommendations": selected, "metrics": candidate_metrics},
        "verdict": {"artistDiversityImproved": diversity_improved,
                    "artistCapSatisfied": candidate_metrics["maxSongsPerArtist"] <= MAX_SONGS_PER_ARTIST,
                    "rangeProxySatisfied": not range_available or candidate_metrics["outsideMassPassingCount"] == len(selected),
                    "qualityClaim": "이 결과는 다양성·음역 프록시 검증입니다. 사용자의 부르고 싶은지 평가, 완료율 또는 녹음 품질 없이는 추천 품질 향상을 확정하지 않습니다."},
    }
