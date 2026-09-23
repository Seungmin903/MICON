"""두 축(ECAPA·Singer-BYOL) 배치 — `two-axis-quota-v1`.

청취 검증에서 두 축의 1위가 귀로 구별되지 않고 개인차가 컸다(AI 파트, 2026-09-07). 그래서 한 점수로 합치지 않고
축별 상위를 정해진 **할당**(2:2:1)으로 뽑아 라벨과 함께 내려주고, 사용자가 어느 라벨을 고르는지 로그로 모아 비율을 정한다.

- 누가 들어오나: 할당. ECAPA 상위 2, Singer-BYOL 상위 2, CONSENSUS 1 (두 축 모두 상위 CONSENSUS_TOP_N 안, 약한 축 기준 정렬).
- 어떤 순서로 보이나: `final_score` 내림차순. 화면은 위치가 앞일수록 크게 보이므로 위치 = 적합도여야 한다.
  후보군에 따라 값이 바뀌는 확신도(margin)는 순서에 쓰지 않고 `confidence` 로 로그에만 남긴다(TIMBRE_RECOMMENDER.md 원칙).
- 클라이언트는 이 순서를 재정렬하지 않는다.

입력은 `scoring.recommend()` 의 후보 전체(top_k 를 크게 준 것), 출력은 최종 응답의 `recommendations[]` 와 `algorithm` 조각.
파라미터는 사용자 평가 후 조정한다.
"""

from __future__ import annotations

import re
from typing import Any, Iterable, Mapping, Sequence

from .pitch_range import MAX_OUTSIDE_RANGE_RATIO

COMPOSITION_VERSION = "two-axis-quota-v2-artist-cap"
FINAL_SCORE_COMPOSITION_VERSION = "final-score-v1"

# 할당. 클라이언트는 이 값을 알 필요 없다(응답 algorithm.composition 에 실림). top_k 가 5 를 넘으면 같은 비율로 늘린다.
QUOTA = {"ecapa": 2, "singer_byol": 2, "consensus": 1}
# CONSENSUS 조건: 두 축 모두 이 순위 안. 약한 축 백분위가 높은 순.
CONSENSUS_TOP_N = 10
ORDER_RULE = "final_score desc"

# 응답의 axis 값과 화면 라벨. FE 의 TimbreAxis enum 과 1:1.
AXIS_NAME = {"ecapa": "ECAPA", "singer_byol": "SINGER_BYOL", "consensus": "CONSENSUS"}
AXIS_LABEL = {
    "ecapa": "목소리 물성이 가까운 가수",
    "singer_byol": "부르는 방식이 비슷한 가수",
    "consensus": "두 축 모두 가까운 가수",
}

# 음역 게이트: 음역 점수가 이 값 아래(또는 곡 프로파일 unreliable)면 할당에서 제외. 사용자 평가 후 조정.
DEFAULT_MIN_RANGE_SCORE = 0.35
# 모델이 같은 가수를 여러 곡 높게 평가해도, 최종 '내 목소리' 추천에서는
# 한 가수의 곡을 최대 두 곡만 표시한다. 이는 모델 평가지표가 아닌 제품 구성 정책이다.
MAX_SONGS_PER_ARTIST = 2

# 언어 게이트. 카탈로그 metadata.languages 는 ISO 639-2. mul/zxx(다국어·가사 없음)는 항상 통과.
CATALOG_LANGUAGE = {"kor": "ko", "eng": "en", "jpn": "jp"}
# Only explicit language choices constrain catalog languages. Musical genres such as pop,
# dance, ballad, or R&B must never become language gates.
GENRE_LANGUAGE = {
    "영어곡": "en", "일본곡": "jp",
}

_TJ_NUMBER = re.compile(r"(\d+)\s*$")
_AXES = ("ecapa", "singer_byol")


def song_id_of(metadata: Mapping[str, Any] | None) -> str | int | None:
    """Preserve opaque V28 DB IDs; retain numeric caches and TJ demo fallback."""
    song_id = (metadata or {}).get("song_id")
    if (isinstance(song_id, str) and song_id.isascii() and 0 < len(song_id) <= 64
            and all(c.isalnum() or c in "-_" for c in song_id)):
        return song_id
    if isinstance(song_id, int) and not isinstance(song_id, bool) and song_id > 0:
        return song_id
    tj_id = (metadata or {}).get("tj_id")
    if not tj_id:
        return None
    match = _TJ_NUMBER.search(str(tj_id))
    return f"tj-{match.group(1)}" if match else None


def allowed_languages(genres: Iterable[str]) -> set[str] | None:
    """Only explicit language preferences (영어곡/일본곡) limit catalog language."""
    cleaned = [g.strip() for g in genres if g and g.strip()]
    selected = {GENRE_LANGUAGE[genre.lower()] for genre in cleaned if genre.lower() in GENRE_LANGUAGE}
    return selected or None


def track_language(metadata: Mapping[str, Any] | None) -> str | None:
    """ko/en/jp, 알 수 없으면 None(게이트 통과)."""
    raw = (metadata or {}).get("languages") or (metadata or {}).get("language")
    if isinstance(raw, list):
        raw = raw[0] if raw else None
    if not raw:
        return None
    return CATALOG_LANGUAGE.get(str(raw).lower())


def _percentiles(values: Sequence[float]) -> list[float]:
    """값이 낮은 후보의 비율. n=1 이면 1.0. 0~1."""
    n = len(values)
    if n <= 1:
        return [1.0] * n
    ordered = sorted(values)
    return [round(sum(1 for o in ordered if o < v) / (n - 1), 4) for v in values]


def _public_metadata(metadata: Mapping[str, Any] | None) -> dict[str, Any]:
    m = metadata or {}
    return {
        "album_name": m.get("album_name"),
        "display_image_url": m.get("display_image_url"),
        "genres": list(m.get("genres") or []),
        "language": track_language(m),
        "release_date": m.get("release_date"),
        "tj_id": m.get("tj_id"),
    }


def _axis_value(item: Mapping[str, Any], axis: str) -> float:
    return float(item["models"][axis]["absolute_score"])


def _consistency(item: Mapping[str, Any], axis: str) -> float:
    return float(item["models"][axis].get("match_consistency", 0.0))


def _artist_key(item: Mapping[str, Any]) -> str:
    artist = str(item.get("artist") or "").strip().casefold()
    return artist or f"track:{item['track_id']}"


def _outside_range_ratio(fit: Mapping[str, Any]) -> float:
    """신규 필드가 없는 과거 결과도 coverage로 같은 뜻을 복원한다."""

    return float(fit.get("outsideRangeRatio", 1.0 - float(fit.get("coverage", 0.0))))


def _scaled_quota(top_k: int) -> dict[str, int]:
    """top_k 를 2:2:1 비율로 나눈다. 나머지는 ecapa, singer_byol 순."""
    unit = sum(QUOTA.values())
    base = {axis: (top_k * q) // unit for axis, q in QUOTA.items()}
    for axis in ("ecapa", "singer_byol", "consensus"):
        if sum(base.values()) >= top_k:
            break
        base[axis] += 1
    return base


def compose_two_axis(
    ranked: Sequence[Mapping[str, Any]],
    *,
    top_k: int,
    genres: Iterable[str] = (),
    range_enabled: bool = False,
    min_range_score: float = DEFAULT_MIN_RANGE_SCORE,
    scoring_version: str,
) -> dict[str, Any]:
    """`scoring.recommend()` 후보를 두 축 할당으로 뽑고 final_score 순으로 배치한다. 반환: recommendations + algorithm 조각."""
    genres = list(genres)
    allowed = allowed_languages(genres)

    # 1) 앱이 열 수 있는 곡만 (song_id 없는 곡 제외)
    with_song = [item for item in ranked if song_id_of(item.get("metadata"))]
    no_song_count = len(ranked) - len(with_song)

    # 2) 언어 게이트
    if allowed is None:
        gated = list(with_song)
    else:
        gated = [item for item in with_song if (track_language(item.get("metadata")) in (None, *allowed))]
    language_excluded = len(with_song) - len(gated)

    # 3) 음역 게이트 (음역 점수가 있을 때만)
    if range_enabled:
        def range_ok(item: Mapping[str, Any]) -> bool:
            fit = item.get("range_fit")
            return (
                bool(fit)
                and bool(fit.get("catalogProfileReliable", True))
                and float(fit.get("score", 0.0)) >= min_range_score
                and _outside_range_ratio(fit) <= MAX_OUTSIDE_RANGE_RATIO
            )
        pool = [item for item in gated if range_ok(item)]
    else:
        pool = list(gated)
    range_excluded = len(gated) - len(pool)

    # 4) 축별 순위·백분위
    percentile: dict[str, dict[str, float]] = {item["track_id"]: {} for item in pool}
    order: dict[str, list[Mapping[str, Any]]] = {}
    rank: dict[str, dict[str, int]] = {item["track_id"]: {} for item in pool}
    for axis in _AXES:
        values = [_axis_value(item, axis) for item in pool]
        for item, p in zip(pool, _percentiles(values)):
            percentile[item["track_id"]][axis] = p
        order[axis] = sorted(pool, key=lambda item, a=axis: (-_axis_value(item, a), item["track_id"]))
        for i, item in enumerate(order[axis], start=1):
            rank[item["track_id"]][axis] = i
    # CONSENSUS = 두 축 모두 CONSENSUS_TOP_N 안. 약한 축의 백분위(min)가 높은 순, 같으면 융합 점수.
    for item in pool:
        p = percentile[item["track_id"]]
        p["consensus"] = round(min(p["ecapa"], p["singer_byol"]), 4)
    order["consensus"] = sorted(
        (item for item in pool if max(rank[item["track_id"]][a] for a in _AXES) <= CONSENSUS_TOP_N),
        key=lambda item: (-percentile[item["track_id"]]["consensus"], -float(item["timbre_score"]), item["track_id"]),
    )

    # 5) 할당대로 뽑기 (중복 없이). CONSENSUS 가 모자라면 그 자리는 ecapa/singer_byol 이 번갈아 채운다.
    quota = _scaled_quota(top_k)
    used: set[str] = set()
    artist_counts: dict[str, int] = {}
    picks: list[tuple[str, Mapping[str, Any]]] = []

    def take(axis: str) -> bool:
        for item in order[axis]:
            artist = _artist_key(item)
            if item["track_id"] not in used and artist_counts.get(artist, 0) < MAX_SONGS_PER_ARTIST:
                used.add(item["track_id"])
                artist_counts[artist] = artist_counts.get(artist, 0) + 1
                picks.append((axis, item))
                return True
        return False

    filled = {axis: 0 for axis in quota}
    for axis in ("consensus", "ecapa", "singer_byol"):
        while filled[axis] < quota[axis] and take(axis):
            filled[axis] += 1
    fallback_axes = ("ecapa", "singer_byol")
    i = 0
    misses = 0
    while len(picks) < top_k and len(used) < len(pool):
        if take(fallback_axes[i % 2]):
            filled[fallback_axes[i % 2]] += 1
            misses = 0
        else:
            misses += 1
            if misses >= len(fallback_axes):
                break
        i += 1

    # 6) 순서 = final_score 내림차순. 화면 크기 규칙(앞일수록 크게)과 같은 뜻.
    picks.sort(key=lambda pair: (-float(pair[1]["final_score"]), pair[1]["track_id"]))

    recommendations = []
    for position, (axis, item) in enumerate(picks, start=1):
        fit = item.get("range_fit") if range_enabled else None
        p = percentile[item["track_id"]]
        confidence = min(_consistency(item, a) for a in _AXES) if axis == "consensus" else _consistency(item, axis)
        recommendations.append(
            {
                "position": position,
                "track_id": item["track_id"],
                "song_id": song_id_of(item.get("metadata")),
                "title": item["title"],
                "artist": item["artist"],
                "metadata": _public_metadata(item.get("metadata")),
                "axis": AXIS_NAME[axis],
                "label": AXIS_LABEL[axis],
                "axis_scores": {"ecapa": p["ecapa"], "singer_byol": p["singer_byol"], "consensus": p["consensus"]},
                "axis_rank": {a: rank[item["track_id"]][a] for a in _AXES},
                "confidence": round(confidence, 4),
                "timbre_score": round(float(item["timbre_score"]), 6),
                "range_fit": fit,
                "recommended_key": int(fit["keyShift"]) if fit else None,
                "final_score": round(float(item["final_score"]), 6),
                "scoring_version": scoring_version,
                "composition_version": COMPOSITION_VERSION,
            }
        )

    return {
        "recommendations": recommendations,
        "composition": {
            "version": COMPOSITION_VERSION,
            "quota": quota,
            "filled": filled,
            "consensus_top_n": CONSENSUS_TOP_N,
            "order": ORDER_RULE,
            "candidate_count": len(pool),
            "max_songs_per_artist": MAX_SONGS_PER_ARTIST,
            "unique_artist_count": len(artist_counts),
        },
        "language_gate": {
            "enabled": allowed is not None,
            "genres": genres,
            "allowed": sorted(allowed) if allowed is not None else None,
            "excluded_count": language_excluded,
        },
        "range_filter": {
            "enabled": range_enabled,
            "min_score": min_range_score,
            "max_outside_range_ratio": MAX_OUTSIDE_RANGE_RATIO,
            "excluded_count": range_excluded,
        },
        "catalog_filter": {"excluded_without_song_id": no_song_count},
    }


def compose_by_final_score(
    ranked: Sequence[Mapping[str, Any]],
    *,
    top_k: int,
    genres: Iterable[str] = (),
    range_enabled: bool = False,
    min_range_score: float = DEFAULT_MIN_RANGE_SCORE,
    scoring_version: str,
) -> dict[str, Any]:
    """통과한 후보를 축별 할당 없이 ``final_score`` 순으로 반환한다."""
    genres = list(genres)
    allowed = allowed_languages(genres)
    with_song = [item for item in ranked if song_id_of(item.get("metadata"))]
    no_song_count = len(ranked) - len(with_song)
    if allowed is None:
        gated = list(with_song)
    else:
        gated = [item for item in with_song if track_language(item.get("metadata")) in (None, *allowed)]
    language_excluded = len(with_song) - len(gated)
    if range_enabled:
        def range_ok(item: Mapping[str, Any]) -> bool:
            fit = item.get("range_fit")
            return (
                bool(fit)
                and bool(fit.get("catalogProfileReliable", True))
                and float(fit.get("score", 0.0)) >= min_range_score
                and _outside_range_ratio(fit) <= MAX_OUTSIDE_RANGE_RATIO
            )
        pool = [item for item in gated if range_ok(item)]
    else:
        pool = list(gated)
    range_excluded = len(gated) - len(pool)
    ordered = []
    artist_counts: dict[str, int] = {}
    for item in sorted(pool, key=lambda item: (-float(item["final_score"]), item["track_id"])):
        artist = _artist_key(item)
        if artist_counts.get(artist, 0) >= MAX_SONGS_PER_ARTIST:
            continue
        ordered.append(item)
        artist_counts[artist] = artist_counts.get(artist, 0) + 1
        if len(ordered) == top_k:
            break
    recommendations = []
    for position, item in enumerate(ordered, start=1):
        fit = item.get("range_fit") if range_enabled else None
        recommendations.append({
            "position": position,
            "track_id": item["track_id"],
            "song_id": song_id_of(item.get("metadata")),
            "title": item["title"],
            "artist": item["artist"],
            "metadata": _public_metadata(item.get("metadata")),
            "axis": None,
            "label": "최종 점수 순",
            "axis_scores": None,
            "axis_rank": None,
            "confidence": None,
            "timbre_score": round(float(item["timbre_score"]), 6),
            "range_fit": fit,
            "recommended_key": int(fit["keyShift"]) if fit else None,
            "final_score": round(float(item["final_score"]), 6),
            "scoring_version": scoring_version,
            "composition_version": FINAL_SCORE_COMPOSITION_VERSION,
        })
    return {
        "recommendations": recommendations,
        "composition": {
            "version": FINAL_SCORE_COMPOSITION_VERSION,
            "selection": "final_score",
            "order": ORDER_RULE,
            "candidate_count": len(pool),
            "max_songs_per_artist": MAX_SONGS_PER_ARTIST,
            "unique_artist_count": len(artist_counts),
        },
        "language_gate": {
            "enabled": allowed is not None,
            "genres": genres,
            "allowed": sorted(allowed) if allowed is not None else None,
            "excluded_count": language_excluded,
        },
        "range_filter": {
            "enabled": range_enabled,
            "min_score": min_range_score,
            "max_outside_range_ratio": MAX_OUTSIDE_RANGE_RATIO,
            "excluded_count": range_excluded,
        },
        "catalog_filter": {"excluded_without_song_id": no_song_count},
    }


def apply_composition(result: dict[str, Any], *, top_k: int, genres: Iterable[str] = ()) -> dict[str, Any]:
    """`scoring.recommend()` 결과(top_k 를 크게 준 것)를 최종 응답 모양으로 바꾼다. API 와 샘플 스크립트가 같이 쓴다."""
    algorithm = result["algorithm"]
    composed = compose_by_final_score(
        result["recommendations"],
        top_k=top_k,
        genres=genres,
        range_enabled=bool(algorithm.get("range_scoring", {}).get("enabled")),
        scoring_version=str(algorithm["scoring_version"]),
    )
    result["recommendations"] = composed.pop("recommendations")
    algorithm.update(composed)
    return result
