"""선호 장르를 후보 점수에 반영하는 실험 규칙. 운영 코어는 장르를 언어 판정에만 쓴다.

운영(`timbre_recommender`)에서 설문 장르가 하는 일은 `allowed_languages()` 의 언어
게이트 하나뿐이다. 스타일 태그를 만드는 `selected_tags()` 는 테스트 말고 어디에서도
호출되지 않는다. 그래서 회원이 "발라드"를 골라도 어떤 곡이 뽑히는지는 달라지지 않는다.
이 모듈은 **실험실에서만** 쓰는 장르 가산점을 정의한다. 운영 코어는 건드리지 않는다.

- 카탈로그 `metadata.genres` 는 DB 라벨(한글, 예: "랩/힙합")이고, 예전 번들과 합성
  데이터는 영어 태그("Hip-Hop")다. 두 표기를 같은 canonical key 로 모아 비교한다.
- 가산점은 그 입력의 점수 분포 폭(span = 최고 - 최저)에 비례한다. 영향력 100% 는
  "선호 장르가 먼저, 같은 장르 안에서는 음색 순"과 같은 뜻이 되고 0% 는 현행과 같다.
  절대값을 쓰면 입력마다 점수 폭이 달라 슬라이더의 같은 위치가 다른 세기가 된다.
"""

from __future__ import annotations

from typing import Any, Iterable, Mapping, Sequence

# 표기가 달라도 같은 장르로 보는 별칭. 키는 소문자·공백 제거 후 비교한다.
# 한글은 현재 DB 라벨, 영어는 예전 카탈로그 번들과 합성 데이터의 태그.
GENRE_ALIASES = {
    "발라드": "ballad", "ballad": "ballad",
    "락/메탈": "rock", "록/메탈": "rock", "락": "rock", "록": "rock",
    "rock": "rock", "metal": "rock", "alternative": "rock",
    "ost": "ost", "soundtrack": "ost",
    "알앤비/소울": "rnb", "r&b/소울": "rnb", "r&b": "rnb", "rnb": "rnb", "soul": "rnb",
    "댄스/팝": "dance", "댄스": "dance", "dance": "dance", "electronic": "dance",
    "인디": "indie", "indie": "indie", "singer-songwriter": "indie",
    "성인가요": "trot", "트로트": "trot", "trot": "trot",
    "포크/어쿠스틱": "folk", "포크": "folk", "folk": "folk",
    "j-pop": "jpop", "jpop": "jpop", "j팝": "jpop", "일본곡": "jpop", "anime": "jpop",
    "팝": "pop", "pop": "pop", "영어곡": "pop", "global-pop": "pop",
    "랩/힙합": "hiphop", "힙합": "hiphop", "hip-hop": "hiphop", "hip hop": "hiphop", "rap": "hiphop",
    "클래식": "classic", "classical": "classic",
    "재즈": "jazz", "jazz": "jazz",
    "레게": "reggae", "reggae": "reggae",
    "k-pop": "kpop", "kpop": "kpop", "케이팝": "kpop",
    "holiday": "holiday",
}

# 특정 스타일이 아니라 "한국 대중가요" 정도의 범용 태그. 운영 `selected_tags()` 도 K-Pop 을
# 항상 취향 안으로 본다. 이 태그**만** 달린 곡은 어떤 선호 장르에도 맞는 것으로 센다.
GENERIC_KEYS = frozenset({"kpop"})


def genre_key(label: Any) -> str | None:
    """표기가 다른 장르 라벨을 하나의 canonical key 로. 모르는 라벨이면 None."""
    if label is None:
        return None
    return GENRE_ALIASES.get(str(label).strip().lower())


def genre_keys(labels: Iterable[Any] | None) -> set[str]:
    return {key for key in (genre_key(label) for label in labels or ()) if key}


def unknown_labels(labels: Iterable[Any] | None) -> list[str]:
    """canonical key 를 못 찾은 라벨. 조용히 무시하지 않고 화면에 알린다."""
    return [str(label).strip() for label in labels or ()
            if label is not None and str(label).strip() and not genre_key(label)]


def track_genres(metadata: Mapping[str, Any] | None) -> list[str]:
    return [str(g) for g in (metadata or {}).get("genres") or []]


def track_matches(metadata: Mapping[str, Any] | None, preferred: set[str]) -> bool:
    """이 곡이 선호 장르 안인가. 범용 태그뿐인 곡은 안으로 본다."""
    if not preferred:
        return False
    keys = genre_keys(track_genres(metadata))
    if not keys:
        return False
    return bool(keys & preferred) or keys <= GENERIC_KEYS


def preferred_labels(selected: Sequence[Any] | None, case_genres: Sequence[Any] | None) -> list[str]:
    """실험에서 고른 장르가 있으면 그것을, 없으면 입력 case 에 저장된 취향을 쓴다."""
    chosen = [str(g).strip() for g in selected or () if g is not None and str(g).strip()]
    if chosen:
        return chosen
    return [str(g).strip() for g in case_genres or () if g is not None and str(g).strip()]


def bonus_unit(scores: Iterable[float], weight: float) -> tuple[float, float]:
    """(선호 장르 한 곡에 더할 점수, 점수 분포 폭). 영향력 100% = 폭만큼 = 장르 우선."""
    values = [float(s) for s in scores]
    span = (max(values) - min(values)) if values else 0.0
    if not (weight > 0) or span <= 0:
        return 0.0, span
    return float(weight) * span, span


def catalog_summary(tracks: Iterable[Any]) -> list[dict[str, Any]]:
    """카탈로그에 실제로 들어 있는 장르 라벨과 곡 수. 화면의 선택지가 된다."""
    counts: dict[str, int] = {}
    for track in tracks:
        for label in track_genres(getattr(track, "metadata", None)):
            name = label.strip()
            if name:
                counts[name] = counts.get(name, 0) + 1
    return [{"label": label, "key": genre_key(label), "count": count}
            for label, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))]
