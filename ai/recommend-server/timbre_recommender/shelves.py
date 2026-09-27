"""추천 섹션(shelf) 구성 — `shelves-v1`.

사용자는 섹션을 바꿀 때 **다른 기준의 다른 곡**을 기대한다(2026-09-08 검토). 그래서
- 섹션마다 1차 정렬 지표가 서로 다르고(음색 / 중심음 근접 / 가수 임베딩 / 고음 비율 / 무작위),
- 음역은 순서가 아니라 조건(게이트)으로만 쓰며,
- 한 곡은 우선순위(A→B→C→D→E)에 따라 한 섹션에만 나온다.

랩(Hip-Hop 태그)은 음정을 유지하지 않아 음역 지표가 무의미 → 새로운 발견에만 허용.
곡 프로파일 신뢰도(음표 confidence) 하한 0.55 미만은 음역 조건이 있는 섹션에서 제외(랩·불안정 추출 방지).
"""

from __future__ import annotations

from collections import defaultdict
import random
import logging
import time
from typing import Any, Iterable, Mapping, MutableMapping, Sequence

import numpy as np

from .composition import (
    AXIS_LABEL,
    AXIS_NAME,
    compose_by_final_score,
    song_id_of,
    track_language,
)
from .contracts import MODEL_KEYS, SCORING_VERSION
from .pitch_range import COMFORT_PADDING_SEMITONES, MAX_OUTSIDE_RANGE_RATIO, MODE_WEIGHTS, _score_at_shift, _validated_profile, score_range_fit
from .scoring import RankerConfig, _absolute_similarity, _centroid, _model_track_score

SHELVES_VERSION = "shelves-v5-db-genre-preference"
log = logging.getLogger("uvicorn.error")

# 사용자 평가 후 조정할 값
MIN_PROFILE_CONFIDENCE = 0.55          # 곡 음표 신뢰도 하한 (곡예사2 0.47 제외, 정국 0.63 유지)
VOICE_MAX_KEY_SHIFT = 3                # A: 실제 연습에서 지원하는 키 폭 안이면 "부를 수 있는 곡"
ORIGINAL_KEY_MIN_COVERAGE = 0.95       # B
CHALLENGE_HIGH_EXCESS = (1, 3)         # D: 원키에서 곡 상한이 내 상한(+여유)보다 이만큼 높음
CHALLENGE_HIGH_RATIO = (0.03, 0.20)    # D: 내 상한 위에 머무는 시간 비율 (너무 잠깐/너무 오래 제외)
RAP_TAGS = {"Hip-Hop", "Rap", "Hip Hop", "랩/힙합"}   # DB genre 테이블은 한글 라벨을 쓴다
# 설문 표시명 → 운영 DB 장르명. 영어곡/일본곡도 다른 선택지와 동일하게 취급한다.
GENRE_TAGS = {
    "발라드": {"Ballad"}, "댄스": {"Dance"}, "R&B": {"R&B"}, "록": {"Rock", "Alternative"}, "힙합": {"Hip-Hop"},
    "레게": {"Reggae"}, "인디": {"Singer-Songwriter", "Folk", "Alternative"}, "재즈": {"Jazz"}, "포크": {"Folk"}, "트로트": {"Trot"},
    "OST": {"Soundtrack"}, "팝": {"Pop"}, "J-POP": {"J-Pop"}, "J-POP/애니송": {"J-Pop", "Soundtrack"},
    # BE genre labels and the curated Android survey share these aliases.
    "댄스/팝": {"Dance"}, "R&B/소울": {"R&B"}, "알앤비/소울": {"R&B"},
    "록/메탈": {"Rock", "Alternative", "Metal"}, "락/메탈": {"Rock", "Alternative", "Metal"},
    "랩/힙합": {"Hip-Hop"}, "포크/어쿠스틱": {"Folk", "Singer-Songwriter"}, "성인가요": {"Trot"},
    "영어곡": {"Pop"}, "일본곡": {"J-Pop"},
}
STYLE_TAGS = set().union(*GENRE_TAGS.values())
FOREIGN_STYLE_TAGS = {"Pop", "J-Pop"}
STYLE_ALIASES: dict[str, set[str]] = {}
for genre, tags in GENRE_TAGS.items():
    for alias in (genre, *tags):
        STYLE_ALIASES.setdefault(alias.strip().casefold(), set()).update(tags)
SHELF_META = {
    "voice": ("내 목소리와 닮은 곡", "최종 점수 순. 음역은 게이트만(키 ±3, 음역 밖 시간 비율 ≤ 5%). 키는 부르는 방법으로 표시"),
    "original_key": ("키 안 바꾸고 바로 부를 곡", "최적 키 0 · 원키 커버리지 ≥ 0.95. 곡 중심음이 내 중심음에 가까운 순"),
    "favorite_artists": ("선호 가수 좋아하면", "선호 가수 본인 곡 먼저, 이어서 선호 가수 목소리와 닮은 가수의 곡"),
    "challenge": ("고음 한 단계 도전", "원키에서 곡 상한이 내 상한보다 1~3반음 높고 그 구간이 3~20%인 곡. 고음 비율 작은 순 (실험)"),
    "discovery": ("새로운 발견", "선택 장르와 장르 미상 곡 중 다른 섹션에 뽑히지 않은 곡. 앱이 새로고침마다 다시 섞는다"),
}


def is_rap(metadata: Mapping[str, Any] | None) -> bool:
    return bool(RAP_TAGS & set((metadata or {}).get("genres") or []))


def selected_tags(genres: Iterable[str]) -> set[str]:
    tags: set[str] = set()
    for g in genres:
        cleaned = g.strip()
        tags |= STYLE_ALIASES.get(cleaned.casefold(), set())
    return tags


def _genre_tier(row: Mapping[str, Any], selected: set[str], *, enabled: bool) -> int:
    """Selected DB genres first, unknown metadata second, other known genres excluded."""
    if not enabled or not selected:
        return 0
    raw_tags = (row.get("metadata") or {}).get("genres") or []
    tags = set().union(*(STYLE_ALIASES.get(str(tag).strip().casefold(), set()) for tag in raw_tags))
    # A foreign bucket is a deliberate survey choice, even if its song also has an
    # ambiguous style name such as 알앤비/소울. Do not admit it via a Korean style.
    foreign = tags & FOREIGN_STYLE_TAGS
    if foreign and not foreign & selected:
        return 2
    if tags & selected:
        return 0
    if not tags & STYLE_TAGS:
        return 1
    return 2


def _timbre_scores(
    query: Mapping[str, np.ndarray], catalog, track, config: RankerConfig,
    query_centroids: Mapping[str, np.ndarray],
    candidate_centroids: dict[str, np.ndarray] | None = None,
) -> tuple[dict[str, dict[str, float]], dict[str, np.ndarray]]:
    out = {}
    centroids = {} if candidate_centroids is None else candidate_centroids
    for key in MODEL_KEYS:
        candidate = catalog.embeddings(track, key)
        if key not in centroids:
            centroids[key] = _centroid(candidate)
        score = _model_track_score(
            query[key], candidate, centroid_weight=config.centroid_weight,
            segment_weight=config.segment_weight, query_centroid=query_centroids[key],
            candidate_centroid=centroids[key],
        )
        score["absolute_score"] = _absolute_similarity(score["raw_score"])
        out[key] = score
    return out, centroids


def _artist_absolute_scores(
    artist_query: Mapping[str, np.ndarray], catalog,
    candidates: list[tuple[Any, dict[str, Any], dict[str, np.ndarray]]],
    config: RankerConfig, query_centroids: Mapping[str, np.ndarray],
) -> dict[str, list[float]]:
    """Score favorite artists in bounded batches without changing the per-track reductions."""
    scores: dict[str, list[float]] = {key: [0.0] * len(candidates) for key in MODEL_KEYS}
    for key in MODEL_KEYS:
        query = artist_query[key]
        groups: dict[int, list[tuple[int, np.ndarray, np.ndarray]]] = defaultdict(list)
        for index, (track, _, centroids) in enumerate(candidates):
            embedding = catalog.embeddings(track, key)
            groups[len(embedding)].append((index, embedding, centroids[key]))
        for segment_count, group in groups.items():
            # Cap the largest temporary similarity matrix at roughly 16 MiB.
            batch_size = max(1, min(128, 4_000_000 // max(1, len(query) * segment_count)))
            for offset in range(0, len(group), batch_size):
                batch = group[offset : offset + batch_size]
                embeddings = np.concatenate([item[1] for item in batch], axis=0)
                similarities = (query @ embeddings.T).reshape(len(query), len(batch), segment_count)
                query_coverage = np.median(np.max(similarities, axis=2), axis=0)
                candidate_coverage = np.median(np.max(similarities, axis=0), axis=1)
                for position, (index, _, candidate_centroid) in enumerate(batch):
                    centroid_similarity = float(query_centroids[key] @ candidate_centroid)
                    segment_coverage = 0.5 * (
                        float(query_coverage[position]) + float(candidate_coverage[position])
                    )
                    raw_score = (config.centroid_weight * centroid_similarity
                                 + config.segment_weight * segment_coverage)
                    scores[key][index] = _absolute_similarity(raw_score)
    return scores


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
    user = _validated_profile(query_profile, label="query") if query_profile else None
    user_hi = (user["coreHighMidi"] + COMFORT_PADDING_SEMITONES) if user else None
    weights = MODEL_KEYS and {"ecapa": config.base_ecapa_weight, "singer_byol": 1.0 - config.base_ecapa_weight}
    fw = MODE_WEIGHTS.get(mode, MODE_WEIGHTS["timbre"])
    rows = []
    started = time.perf_counter()
    query_centroids = {key: _centroid(query[key]) for key in MODEL_KEYS}
    artist_centroids = {key: _centroid(artist_query[key]) for key in MODEL_KEYS} if artist_query is not None else None
    timbre_seconds = artist_seconds = range_seconds = 0.0
    considered = range_scored = 0
    artist_candidates: list[tuple[Any, dict[str, Any], dict[str, np.ndarray]]] = []
    for track in catalog.tracks:
        meta = track.metadata or {}
        song_id = song_id_of(meta)
        if not song_id:
            continue
        considered += 1
        phase_started = time.perf_counter()
        models, candidate_centroids = _timbre_scores(query, catalog, track, config, query_centroids)
        timbre = float(sum(weights[k] * models[k]["absolute_score"] for k in MODEL_KEYS))
        timbre_seconds += time.perf_counter() - phase_started
        row: dict[str, Any] = {
            "track_id": track.track_id, "song_id": song_id, "title": track.title, "artist": track.artist, "metadata": meta,
            "models": models, "timbre_score": timbre, "is_rap": is_rap(meta),
            "artist_similarity": None, "range_fit": None, "final_score": timbre, "melodic": True,
            "coverage0": None, "center0": None, "high_excess0": None, "high_ratio0": None,
        }
        if artist_query is not None:
            artist_candidates.append((track, row, candidate_centroids))
        cand = ranges.profiles.get(track.track_id) if ranges is not None else None
        if user is not None and cand is not None:
            phase_started = time.perf_counter()
            range_scored += 1
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
            range_seconds += time.perf_counter() - phase_started
        rows.append(row)
    if artist_query is not None:
        phase_started = time.perf_counter()
        artist_scores = _artist_absolute_scores(artist_query, catalog, artist_candidates, config, artist_centroids)
        for index, (_, row, _) in enumerate(artist_candidates):
            row["artist_similarity"] = float(sum(
                weights[key] * artist_scores[key][index] for key in MODEL_KEYS
            ))
        artist_seconds = time.perf_counter() - phase_started
    total_seconds = time.perf_counter() - started
    log.info(
        "event=recommendation_all_tracks_profile tracks=%d considered=%d range_scored=%d "
        "artist_query_enabled=%d timbre_ms=%.2f artist_ms=%.2f range_ms=%.2f "
        "other_ms=%.2f total_ms=%.2f",
        len(catalog.tracks), considered, range_scored, int(artist_query is not None),
        timbre_seconds * 1000, artist_seconds * 1000, range_seconds * 1000,
        (total_seconds - timbre_seconds - artist_seconds - range_seconds) * 1000,
        total_seconds * 1000,
    )
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
    genre_preferences_enabled: bool = False,
    range_enabled: bool,
    include_challenge: bool = False,
    seed: int | None = None,
    diagnostics: MutableMapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    genres = list(genres)
    all_rows = list(rows)
    preferred_tags = selected_tags(genres)
    def genre_tier(row: Mapping[str, Any]) -> int:
        return _genre_tier(row, preferred_tags, enabled=genre_preferences_enabled)
    tier_counts = {
        "selected": sum(genre_tier(row) == 0 for row in all_rows),
        "neutral": sum(genre_tier(row) == 1 for row in all_rows),
        "excludedKnown": sum(genre_tier(row) == 2 for row in all_rows),
    }
    if genre_preferences_enabled and preferred_tags:
        rows = [row for row in all_rows if genre_tier(row) < 2]
    artists = {a.strip() for a in preferred_artists if a and a.strip()}
    used: set[str] = set()
    shelves: list[dict[str, Any]] = []
    candidate_counts: dict[str, int] = {}
    returned_counts: dict[str, int] = {}

    def emit(key: str, picked: list[tuple[Mapping[str, Any], dict[str, Any], str | None, str | None]], extra: dict[str, Any] | None = None):
        candidate_counts.setdefault(key, len(picked))
        items = []
        for row, metrics, axis, label in picked:
            if row["track_id"] in used or len(items) >= top_k:
                continue
            used.add(row["track_id"])
            items.append(_item(row, len(items) + 1, key, metrics, axis, label))
        title, metric = SHELF_META[key]
        shelves.append({"key": key, "title": title, "metric": metric, "items": items, **(extra or {})})
        returned_counts[key] = len(items)

    def singable(r):   # 음역 조건이 있는 섹션의 공통 게이트
        fit = r.get("range_fit")
        outside_ratio = float(fit.get("outsideRangeRatio", 1.0 - float(fit.get("coverage", 0.0)))) if fit else 1.0
        return (range_enabled and r["melodic"] and fit is not None
                and outside_ratio <= MAX_OUTSIDE_RANGE_RATIO)

    # A. 내 목소리: 최종 점수 순. 게이트: 부를 수 있고 |키| ≤ 3, 랩 제외
    pool_a = [r for r in rows if not r["is_rap"] and (not range_enabled or (singable(r) and abs(int(r["range_fit"]["keyShift"])) <= VOICE_MAX_KEY_SHIFT))]
    candidate_counts["voice"] = len(pool_a)
    ranked_pool_a = [dict(row, final_score=(2 - genre_tier(row)) + float(row["final_score"]) * 0.001) for row in pool_a]
    composed = compose_by_final_score(ranked_pool_a, top_k=top_k, genres=(), range_enabled=range_enabled, scoring_version=SCORING_VERSION)
    by_id = {r["track_id"]: r for r in pool_a}
    emit("voice", [(by_id[it["track_id"]], {}, it["axis"], it["label"]) for it in composed["recommendations"]],
         {"composition": composed["composition"]})

    # B. 키 안 바꾸고: 최적 키 0 · 원키 커버리지 ≥ 0.95 → 중심음 근접 순 (음색 미사용)
    pool_b = [r for r in rows if singable(r) and int(r["range_fit"]["keyShift"]) == 0 and r["coverage0"] >= ORIGINAL_KEY_MIN_COVERAGE]
    pool_b.sort(key=lambda r: (genre_tier(r), -r["center0"], -r["coverage0"], r["track_id"]))
    emit("original_key", [(r, {"coverage0": r["coverage0"], "center0": r["center0"]}, None, None) for r in pool_b])

    # C. 선호 가수: 본인 곡(음색 순) → 가수 임베딩 유사도 순. 부를 수 있는 곡만, 랩 제외
    if artists and any(r.get("artist_similarity") is not None for r in rows):
        candidates = [r for r in rows if r["artist"] in artists and not r["is_rap"]]
        candidates += [r for r in rows if r["artist"] not in artists and not r["is_rap"] and (not range_enabled or singable(r))]
        candidates.sort(key=lambda r: (genre_tier(r), 0 if r["artist"] in artists else 1,
                                       -(r["timbre_score"] if r["artist"] in artists else (r["artist_similarity"] or 0))))
        emit("favorite_artists", [(r, {"artist_similarity": r["artist_similarity"], "own": r["artist"] in artists}, None, None) for r in candidates],
             {"preferred_artists": sorted(artists)})

    # D. 고음 도전 (실험): 원키 상한 초과 1~3반음, 상한 위 시간 3~20%, 저음 쪽은 안 → 고음 비율 작은 순
    if include_challenge:
        lo, hi = CHALLENGE_HIGH_EXCESS
        rlo, rhi = CHALLENGE_HIGH_RATIO
        pool_d = [r for r in rows if range_enabled and r["melodic"] and r["range_fit"] is not None and lo <= (r["high_excess0"] or 0) <= hi
                  and rlo <= (r["high_ratio0"] or 0) <= rhi and r["range_fit"]["lowCoreExcessSemitones"] == 0]
        pool_d.sort(key=lambda r: (genre_tier(r), r["high_ratio0"], -r["coverage0"], r["track_id"]))
        emit("challenge", [(r, {"high_excess0": r["high_excess0"], "high_ratio0": r["high_ratio0"], "coverage0": r["coverage0"]}, None, None) for r in pool_d],
             {"experimental": True})

    # E. 새로운 발견도 알려진 비선호 장르는 제외한다. 선택 장르의 남은 곡을
    #    먼저 섞고, 장르 미상 곡은 뒤 채움으로만 쓴다.
    remaining = [r for r in rows if r["track_id"] not in used]
    pool_e = [r for r in remaining if genre_tier(r) == 0]
    neutral_e = [r for r in remaining if genre_tier(r) == 1]
    rng = random.Random(seed)
    rng.shuffle(pool_e)
    rng.shuffle(neutral_e)
    pool_e.extend(neutral_e)
    emit("discovery", [(r, {"timbre_score": r["timbre_score"]}, None, None) for r in pool_e], {"seed": seed})
    if diagnostics is not None:
        diagnostics.update({
            "genrePolicy": "db-tags-v1",
            "genrePreferencesEnabled": bool(genre_preferences_enabled and preferred_tags),
            "selectedTags": sorted(preferred_tags),
            "tierCounts": tier_counts,
            "shelfCandidateCounts": candidate_counts,
            "shelfReturnedCounts": returned_counts,
        })
    return shelves
