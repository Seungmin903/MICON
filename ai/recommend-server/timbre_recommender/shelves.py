"""추천 섹션(shelf) 구성 — `shelves-v1`.

사용자는 섹션을 바꿀 때 **다른 기준의 다른 곡**을 기대한다(2026-09-08 검토). 그래서
- 섹션마다 1차 정렬 지표가 서로 다르고(음색 / 중심음 근접 / 가수 임베딩 / 고음 비율 / 무작위),
- 음역은 순서가 아니라 조건(게이트)으로만 쓰며,
- 한 곡은 우선순위(A→B→C→D→E)에 따라 한 섹션에만 나온다.

랩(Hip-Hop 태그)은 음정을 유지하지 않아 음역 지표가 무의미 → 새로운 발견에만 허용.
곡 프로파일 신뢰도(음표 confidence) 하한 0.55 미만은 음역 조건이 있는 섹션에서 제외(랩·불안정 추출 방지).
"""

from __future__ import annotations

import random
from typing import Any, Iterable, Mapping, Sequence

import numpy as np

from .composition import (
    AXIS_LABEL,
    AXIS_NAME,
    allowed_languages,
    compose_two_axis,
    song_id_of,
    track_language,
)
from .contracts import MODEL_KEYS, SCORING_VERSION
from .pitch_range import COMFORT_PADDING_SEMITONES, MODE_WEIGHTS, _score_at_shift, _validated_profile, score_range_fit
from .scoring import RankerConfig, _absolute_similarity, _model_track_score

SHELVES_VERSION = "shelves-v1"

# 사용자 평가 후 조정할 값
MIN_PROFILE_CONFIDENCE = 0.55          # 곡 음표 신뢰도 하한 (곡예사2 0.47 제외, 정국 0.63 유지)
VOICE_MAX_KEY_SHIFT = 3                # A: 이 폭 안의 키 조정으로 부담 0 이면 "부를 수 있는 곡"
ORIGINAL_KEY_MIN_COVERAGE = 0.95       # B
CHALLENGE_HIGH_EXCESS = (1, 3)         # D: 원키에서 곡 상한이 내 상한(+여유)보다 이만큼 높음
CHALLENGE_HIGH_RATIO = (0.03, 0.20)    # D: 내 상한 위에 머무는 시간 비율 (너무 잠깐/너무 오래 제외)
DISCOVERY_MAX_PER_GENRE = 3            # E
RAP_TAGS = {"Hip-Hop", "Rap", "Hip Hop"}
# 온보딩 설문 장르 → 카탈로그 태그. 새로운 발견의 "취향 밖" 판정에 쓴다. K-Pop 은 범용 태그라 항상 "취향 안"으로 본다.
GENRE_TAGS = {
    "발라드": {"Ballad"}, "댄스": {"Dance"}, "R&B": {"R&B"}, "팝": {"Pop"}, "록": {"Rock", "Alternative"}, "힙합": {"Hip-Hop"},
    "레게": {"Reggae"}, "인디": {"Singer-Songwriter", "Folk", "Alternative"}, "재즈": {"Jazz"}, "포크": {"Folk"}, "트로트": {"Trot"},
    "OST": {"Soundtrack"}, "J-POP": {"J-Pop"},
}
SHELF_META = {
    "voice": ("내 목소리와 닮은 곡", "음색 유사도 순. 음역은 게이트만(키 ±3 안에서 부담 없음). 키는 부르는 방법으로 표시"),
    "original_key": ("키 안 바꾸고 바로 부를 곡", "최적 키 0 · 원키 커버리지 ≥ 0.95. 곡 중심음이 내 중심음에 가까운 순"),
    "favorite_artists": ("선호 가수 좋아하면", "선호 가수 본인 곡 먼저, 이어서 선호 가수 목소리와 닮은 가수의 곡"),
    "challenge": ("고음 한 단계 도전", "원키에서 곡 상한이 내 상한보다 1~3반음 높고 그 구간이 3~20%인 곡. 고음 비율 작은 순 (실험)"),
    "discovery": ("새로운 발견", "선택 안 한 장르 중 부를 수 있고 음색 중앙값 이상인 곡을 가수·장르 중복 없이 섞어 매일 다른 순서"),
}


def is_rap(metadata: Mapping[str, Any] | None) -> bool:
    return bool(RAP_TAGS & set((metadata or {}).get("genres") or []))


def selected_tags(genres: Iterable[str]) -> set[str]:
    tags = {"K-Pop"}
    for g in genres:
        tags |= GENRE_TAGS.get(g.strip(), set())
    return tags


def _timbre_scores(query: Mapping[str, np.ndarray], catalog, track, config: RankerConfig) -> dict[str, dict[str, float]]:
    out = {}
    for key in MODEL_KEYS:
        score = _model_track_score(query[key], catalog.embeddings(track, key), centroid_weight=config.centroid_weight, segment_weight=config.segment_weight)
        score["absolute_score"] = _absolute_similarity(score["raw_score"])
        out[key] = score
    return out


def score_all_tracks(
    catalog,
    ranges,
    query: Mapping[str, np.ndarray],
    *,
    query_profile: Mapping[str, Any] | None,
    mode: str = "timbre",
    genres: Iterable[str] = (),
    artist_query: Mapping[str, np.ndarray] | None = None,
    config: RankerConfig | None = None,
) -> list[dict[str, Any]]:
    """카탈로그 전 곡에 음색·음역 지표를 붙인 행 목록. 섹션들이 이 표 하나를 나눠 쓴다."""
    config = config or RankerConfig()
    allowed = allowed_languages(genres)
    user = _validated_profile(query_profile, label="query") if query_profile else None
    user_hi = (user["coreHighMidi"] + COMFORT_PADDING_SEMITONES) if user else None
    weights = MODEL_KEYS and {"ecapa": config.base_ecapa_weight, "singer_byol": 1.0 - config.base_ecapa_weight}
    fw = MODE_WEIGHTS.get(mode, MODE_WEIGHTS["timbre"])
    rows = []
    for track in catalog.tracks:
        meta = track.metadata or {}
        song_id = song_id_of(meta)
        if not song_id:
            continue
        lang = track_language(meta)
        if allowed is not None and lang is not None and lang not in allowed:
            continue
        models = _timbre_scores(query, catalog, track, config)
        timbre = float(sum(weights[k] * models[k]["absolute_score"] for k in MODEL_KEYS))
        row: dict[str, Any] = {
            "track_id": track.track_id, "song_id": song_id, "title": track.title, "artist": track.artist, "metadata": meta,
            "models": models, "timbre_score": timbre, "is_rap": is_rap(meta),
            "artist_similarity": None, "range_fit": None, "final_score": timbre, "melodic": True,
            "coverage0": None, "center0": None, "high_excess0": None, "high_ratio0": None,
        }
        if artist_query is not None:
            am = _timbre_scores(artist_query, catalog, track, config)
            row["artist_similarity"] = float(sum(weights[k] * am[k]["absolute_score"] for k in MODEL_KEYS))
        cand = ranges.profiles.get(track.track_id) if ranges is not None else None
        if user is not None and cand is not None:
            conf = float(cand.get("confidence", 1.0))
            row["melodic"] = bool(cand.get("reliable", True)) and conf >= MIN_PROFILE_CONFIDENCE and not row["is_rap"]
            best = score_range_fit(user, cand)
            at0 = _score_at_shift(user, cand, 0)
            row["range_fit"] = best
            row["final_score"] = float(fw["timbre"] * timbre + fw["range"] * best["score"])
            row["coverage0"] = float(at0["coverage"])
            row["center0"] = float(at0["centerScore"])
            row["high_excess0"] = int(cand["coreHighMidi"]) - user_hi
            hist = np.asarray(cand["histogram"], dtype=float)
            midi = np.arange(len(hist)) + int(cand.get("histogramMidiMin", 36))
            row["high_ratio0"] = float(hist[midi > user_hi].sum())
        rows.append(row)
    return rows


def _item(row: Mapping[str, Any], position: int, shelf: str, metrics: dict[str, Any], axis: str | None = None, label: str | None = None) -> dict[str, Any]:
    fit = row.get("range_fit")
    meta = row["metadata"]
    return {
        "position": position, "shelf": shelf,
        "track_id": row["track_id"], "song_id": row["song_id"], "title": row["title"], "artist": row["artist"],
        "metadata": {
            "album_name": meta.get("album_name"), "display_image_url": meta.get("display_image_url"), "genres": list(meta.get("genres") or []),
            "language": track_language(meta), "release_date": meta.get("release_date"), "tj_id": meta.get("tj_id"),
            "artist_gender": meta.get("artist_gender"),        # 아직 데이터 없음(null). 성별 필터용 자리
        },
        "axis": axis, "label": label, "axis_scores": None, "axis_rank": None, "confidence": None,
        "timbre_score": round(float(row["timbre_score"]), 6),
        "range_fit": fit, "recommended_key": int(fit["keyShift"]) if fit else None,
        "final_score": round(float(row["final_score"]), 6),
        "metrics": {k: (round(v, 4) if isinstance(v, float) else v) for k, v in metrics.items()},
        "scoring_version": SCORING_VERSION, "composition_version": SHELVES_VERSION,
    }


def build_shelves(
    rows: Sequence[Mapping[str, Any]],
    *,
    top_k: int = 8,
    genres: Iterable[str] = (),
    preferred_artists: Iterable[str] = (),
    range_enabled: bool,
    include_challenge: bool = False,
    seed: int | None = None,
) -> list[dict[str, Any]]:
    genres = list(genres)
    artists = {a.strip() for a in preferred_artists if a and a.strip()}
    used: set[str] = set()
    shelves: list[dict[str, Any]] = []

    def emit(key: str, picked: list[tuple[Mapping[str, Any], dict[str, Any], str | None, str | None]], extra: dict[str, Any] | None = None):
        items = []
        for row, metrics, axis, label in picked:
            if row["track_id"] in used or len(items) >= top_k:
                continue
            used.add(row["track_id"])
            items.append(_item(row, len(items) + 1, key, metrics, axis, label))
        title, metric = SHELF_META[key]
        shelves.append({"key": key, "title": title, "metric": metric, "items": items, **(extra or {})})

    def singable(r):   # 음역 조건이 있는 섹션의 공통 게이트
        fit = r.get("range_fit")
        return range_enabled and r["melodic"] and fit is not None and fit["totalCoreExcessSemitones"] == 0

    # A. 내 목소리: 두 축 할당(라벨 실험 유지) → final_score 순. 게이트: 부를 수 있고 |키| ≤ 3, 랩 제외
    pool_a = [r for r in rows if not r["is_rap"] and (not range_enabled or (singable(r) and abs(int(r["range_fit"]["keyShift"])) <= VOICE_MAX_KEY_SHIFT))]
    composed = compose_two_axis(pool_a, top_k=top_k, genres=(), range_enabled=range_enabled, scoring_version=SCORING_VERSION)
    by_id = {r["track_id"]: r for r in pool_a}
    emit("voice", [(by_id[it["track_id"]], {"axis_scores": it["axis_scores"], "confidence": it["confidence"]}, it["axis"], it["label"]) for it in composed["recommendations"]],
         {"composition": composed["composition"]})

    # B. 키 안 바꾸고: 최적 키 0 · 원키 커버리지 ≥ 0.95 → 중심음 근접 순 (음색 미사용)
    pool_b = [r for r in rows if singable(r) and int(r["range_fit"]["keyShift"]) == 0 and r["coverage0"] >= ORIGINAL_KEY_MIN_COVERAGE]
    pool_b.sort(key=lambda r: (-r["center0"], -r["coverage0"], r["track_id"]))
    emit("original_key", [(r, {"coverage0": r["coverage0"], "center0": r["center0"]}, None, None) for r in pool_b])

    # C. 선호 가수: 본인 곡(음색 순) → 가수 임베딩 유사도 순. 부를 수 있는 곡만, 랩 제외
    if artists and any(r.get("artist_similarity") is not None for r in rows):
        own = sorted([r for r in rows if r["artist"] in artists and not r["is_rap"]], key=lambda r: -r["timbre_score"])
        others = sorted([r for r in rows if r["artist"] not in artists and not r["is_rap"] and (not range_enabled or singable(r))], key=lambda r: -(r["artist_similarity"] or 0))
        emit("favorite_artists", [(r, {"artist_similarity": r["artist_similarity"], "own": r["artist"] in artists}, None, None) for r in own + others],
             {"preferred_artists": sorted(artists)})

    # D. 고음 도전 (실험): 원키 상한 초과 1~3반음, 상한 위 시간 3~20%, 저음 쪽은 안 → 고음 비율 작은 순
    if include_challenge:
        lo, hi = CHALLENGE_HIGH_EXCESS
        rlo, rhi = CHALLENGE_HIGH_RATIO
        pool_d = [r for r in rows if range_enabled and r["melodic"] and r["range_fit"] is not None and lo <= (r["high_excess0"] or 0) <= hi
                  and rlo <= (r["high_ratio0"] or 0) <= rhi and r["range_fit"]["lowCoreExcessSemitones"] == 0]
        pool_d.sort(key=lambda r: (r["high_ratio0"], -r["coverage0"], r["track_id"]))
        emit("challenge", [(r, {"high_excess0": r["high_excess0"], "high_ratio0": r["high_ratio0"], "coverage0": r["coverage0"]}, None, None) for r in pool_d],
             {"experimental": True})

    # E. 새로운 발견: 취향 밖 장르, 부를 수 있음(랩은 음역 조건 없이 허용), 음색 중앙값 이상 → 가수·장르 중복 없이 섞기
    tags = selected_tags(genres)
    median = float(np.median([r["timbre_score"] for r in rows])) if rows else 0.0
    pool_e = [r for r in rows if (r["metadata"].get("genres") or []) and not (set(r["metadata"]["genres"]) & tags)
              and r["timbre_score"] >= median and (r["is_rap"] or not range_enabled or singable(r))]
    random.Random(seed).shuffle(pool_e)
    seen_artist: set[str] = set()
    per_genre: dict[str, int] = {}
    picked_e = []
    for r in pool_e:
        g = r["metadata"]["genres"][0]
        if r["artist"] in seen_artist or per_genre.get(g, 0) >= DISCOVERY_MAX_PER_GENRE:
            continue
        seen_artist.add(r["artist"]); per_genre[g] = per_genre.get(g, 0) + 1
        picked_e.append((r, {"genre": g, "timbre_score": r["timbre_score"]}, None, None))
    emit("discovery", picked_e, {"excluded_tags": sorted(tags), "seed": seed})
    return shelves
