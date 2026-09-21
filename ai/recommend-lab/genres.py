"""장르를 추천에 반영하는 실험 규칙. 운영 코어는 장르를 언어 판정에만 쓴다.

현행 운영 흐름은 이렇다.

1. 사용자가 장르를 고른다.
2. 장르를 **언어**로 바꾼다(`발라드·댄스·록` → 한국어, `팝·영어곡` → 영어, `J-Pop` → 일본어).
3. 그 언어가 아닌 곡을 후보에서 통째로 뺀다.
4. 남은 곡끼리 ECAPA·Singer-BYOL·음역 점수로 경쟁한다.
5. 같은 언어 안에서 "발라드냐 댄스냐"는 순위에 **전혀** 반영되지 않는다.

이 모듈이 실험하는 대안은 다음 세 가지다. 운영 코어는 건드리지 않는다.

- **DB 장르로 통일.** 기준 표기는 DB 라벨(`발라드`, `랩/힙합`, `J-POP` …)이다. 예전 번들과
  설문에 남아 있는 영어 태그(`Ballad`, `Hip-Hop`)와 표기 변형(`록/메탈`)은 DB 라벨로 접어 넣는다.
  canonical 값이 곧 DB 라벨이므로 따로 외울 코드값이 없다.
- **언어 게이트 폐기.** 장르를 언어로 바꾸지 않는다. J-POP 을 안 고른 사람에게 J-POP 이 안 뜨는 것은
  언어 필터가 아니라 아래 감점으로 이뤄진다.
- **선택 안 한 장르 감점.** 하드 필터가 아니라 점수를 깎는다. 그래서 음색이 압도적으로 닮은 곡은
  장르가 달라도 올라올 수 있다. 감점 크기의 기준은 **Top-K 경쟁 구간**이다(아래).

## 감점 크기를 Top-K 경쟁 구간에 맞추는 이유

전체 점수 분포 폭(최고−최저)을 기준으로 삼으면 슬라이더를 조금만 올려도 사실상 하드 필터가 된다.
실제 입력에서 전체 폭은 0.52인데 Top-10 안에서 1위와 10위의 차이는 0.14밖에 안 되기 때문이다.
그래서 기준을 `band = 1위 점수 − K위 점수`(둘 다 감점 전 점수)로 잡는다.

- `감점 = 밀어내기 × band`
- 밀어내기 100% → 선택 안 한 곡은 **감점 전 1위보다 높아야** Top-K 에 들어온다. 즉 "진짜 음색이
  똑같은 수준" 이 아니면 안 뜬다.
- 밀어내기 50% → Top-K 구간의 절반쯤 앞서야 들어온다.
- 밀어내기 0% → 장르를 아예 안 본다(언어 게이트까지 끄면 장르 무관).
"""

from __future__ import annotations

from typing import Any, Iterable, Mapping, Sequence

# 기준 표기는 DB 장르 라벨이다. 왼쪽은 들어올 수 있는 다른 표기, 오른쪽이 DB 라벨.
# 소문자·공백 제거 후 비교한다. DB 라벨 자신도 키로 넣어 둔다.
DB_LABEL_ALIASES = {
    "발라드": "발라드", "ballad": "발라드",
    "락/메탈": "락/메탈", "록/메탈": "락/메탈", "락": "락/메탈", "록": "락/메탈",
    "rock": "락/메탈", "metal": "락/메탈", "alternative": "락/메탈",
    "ost": "OST", "soundtrack": "OST",
    "알앤비/소울": "알앤비/소울", "r&b/소울": "알앤비/소울", "r&b": "알앤비/소울",
    "rnb": "알앤비/소울", "soul": "알앤비/소울",
    "댄스/팝": "댄스/팝", "댄스": "댄스/팝", "dance": "댄스/팝", "electronic": "댄스/팝",
    "인디": "인디", "indie": "인디", "singer-songwriter": "인디",
    "성인가요": "성인가요", "트로트": "성인가요", "trot": "성인가요",
    "포크/어쿠스틱": "포크/어쿠스틱", "포크": "포크/어쿠스틱", "folk": "포크/어쿠스틱",
    "j-pop": "J-POP", "jpop": "J-POP", "j팝": "J-POP", "일본곡": "J-POP", "anime": "J-POP",
    "팝": "팝", "pop": "팝", "영어곡": "팝", "global-pop": "팝",
    "랩/힙합": "랩/힙합", "힙합": "랩/힙합", "hip-hop": "랩/힙합", "hip hop": "랩/힙합", "rap": "랩/힙합",
    "클래식": "클래식", "classical": "클래식",
    "재즈": "재즈", "jazz": "재즈",
    "레게": "레게", "reggae": "레게",
}

# 스타일이 아니라 "한국 대중가요" 정도의 범용 태그. 이 태그만 달린 곡은 어떤 선택에도 맞는 것으로 보고
# 감점하지 않는다. 운영 `selected_tags()` 도 K-Pop 을 항상 취향 안으로 봤다.
GENERIC_LABELS = frozenset({"K-Pop", "KPOP", "케이팝"})
_GENERIC_KEYS = frozenset(label.strip().lower() for label in GENERIC_LABELS)


def canonical(label: Any) -> str | None:
    """어떤 표기로 들어오든 DB 장르 라벨로. 모르는 라벨이면 None."""
    if label is None:
        return None
    return DB_LABEL_ALIASES.get(str(label).strip().lower())


def canonical_set(labels: Iterable[Any] | None) -> set[str]:
    return {name for name in (canonical(label) for label in labels or ()) if name}


def unknown_labels(labels: Iterable[Any] | None) -> list[str]:
    """DB 라벨로 못 접은 것. 조용히 무시하지 않고 화면에 알린다."""
    return [str(label).strip() for label in labels or ()
            if label is not None and str(label).strip() and not canonical(label)
            and str(label).strip().lower() not in _GENERIC_KEYS]


def track_genres(metadata: Mapping[str, Any] | None) -> list[str]:
    return [str(g) for g in (metadata or {}).get("genres") or []]


def is_generic_only(metadata: Mapping[str, Any] | None) -> bool:
    labels = [g.strip().lower() for g in track_genres(metadata) if g and g.strip()]
    return bool(labels) and all(label in _GENERIC_KEYS for label in labels)


def track_matches(metadata: Mapping[str, Any] | None, selected: set[str]) -> bool:
    """이 곡이 선택한 장르 안인가.

    장르를 모르는 곡(태그 없음·미매칭)과 범용 태그뿐인 곡은 감점하지 않는다. 메타데이터가
    비어 있다는 이유로 곡이 조용히 사라지면 안 되기 때문이다. 화면에 그 수를 따로 표시한다.
    """
    if not selected:
        return True
    names = canonical_set(track_genres(metadata))
    if not names:
        return True
    return bool(names & selected) or is_generic_only(metadata)


def has_known_genre(metadata: Mapping[str, Any] | None) -> bool:
    return bool(canonical_set(track_genres(metadata)))


def selected_labels(chosen: Sequence[Any] | None, case_genres: Sequence[Any] | None) -> list[str]:
    """실험에서 고른 장르가 있으면 그것을, 없으면 입력 case 에 저장된 취향을 쓴다."""
    picked = [str(g).strip() for g in chosen or () if g is not None and str(g).strip()]
    if picked:
        return picked
    return [str(g).strip() for g in case_genres or () if g is not None and str(g).strip()]


def competition_band(scores: Iterable[float], top_k: int) -> tuple[float, float, float]:
    """(band, 1위 점수, K위 커트라인). band = 1위 − K위. Top-K 진입을 가르는 실제 점수 폭이다."""
    values = sorted((float(s) for s in scores), reverse=True)
    if not values:
        return 0.0, 0.0, 0.0
    top = values[0]
    cutoff = values[min(max(int(top_k), 1), len(values)) - 1]
    band = top - cutoff
    if band <= 0:
        # Top-K 안이 전부 동점이면 전체 폭으로 물러선다. 그것도 0이면 감점할 근거가 없다.
        band = top - values[-1]
    return max(band, 0.0), top, cutoff


def penalty_unit(scores: Iterable[float], weight: float, top_k: int) -> tuple[float, float, float, float]:
    """(선택 안 한 곡에서 뺄 점수, band, 1위 점수, 커트라인)."""
    band, top, cutoff = competition_band(scores, top_k)
    if not (weight > 0) or band <= 0:
        return 0.0, band, top, cutoff
    return float(weight) * band, band, top, cutoff


def catalog_summary(tracks: Iterable[Any]) -> list[dict[str, Any]]:
    """카탈로그에 실제로 들어 있는 장르 라벨과 곡 수. 화면의 선택지가 된다."""
    counts: dict[str, int] = {}
    for track in tracks:
        for label in track_genres(getattr(track, "metadata", None)):
            name = label.strip()
            if name:
                counts[name] = counts.get(name, 0) + 1
    return [{"label": label, "canonical": canonical(label), "count": count}
            for label, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))]
