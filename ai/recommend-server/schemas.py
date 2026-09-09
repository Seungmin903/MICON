"""추천 응답 스키마 (pydantic). 이 파일이 BE·FE 계약의 원본이다.

`docs/response-schema.json`(JSON Schema)과 `docs/sample-response.json` 은 `scripts/make_sample_response.py` 가 여기서 만든다.
필드를 바꾸면 그 스크립트를 다시 돌려 두 문서를 갱신한다.

응답의 중심은 `result.shelves[]` — 섹션(shelf)마다 기준이 다르고 곡이 겹치지 않는다(shelves-v1).
`result.recommendations[]` 는 첫 섹션(내 목소리)의 items 복사본으로, 이전 클라이언트 호환용.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class AxisScores(BaseModel):
    """축별 0~1 백분위. 표시용이 아니라 로그·디버그용."""

    ecapa: float = Field(ge=0, le=1)
    singer_byol: float = Field(ge=0, le=1)
    consensus: float = Field(ge=0, le=1)


class RangeFit(BaseModel):
    """음역 적합도 상세 (`vocal-range-keyshift-continuous-v2`). 나머지 서버 필드는 통과시킨다."""

    model_config = ConfigDict(extra="allow")

    scoringVersion: str
    score: float = Field(ge=0, le=1)
    coverage: float = Field(ge=0, le=1)
    centerScore: float = Field(ge=0, le=1)
    spanFit: float = Field(ge=0, le=1)
    softFit: float = Field(ge=0, le=1)
    keyShift: int
    songCoreLowMidi: int
    songCoreHighMidi: int
    catalogProfileReliable: bool


class TrackMetadata(BaseModel):
    album_name: str | None = None
    display_image_url: str | None = None
    genres: list[str] = []
    language: Literal["ko", "en", "jp"] | None = None
    release_date: str | None = None
    tj_id: str | None = None
    artist_gender: Literal["female", "male", "mixed"] | None = Field(default=None, description="성별 필터용. 카탈로그에 아직 데이터 없음 → null")


class Recommendation(BaseModel):
    position: int = Field(ge=1, description="섹션 안 위치. 서버가 정한 순서이며 클라이언트는 재정렬하지 않는다")
    shelf: str | None = Field(default=None, description="소속 섹션 key (voice | original_key | favorite_artists | challenge | discovery)")
    track_id: str = Field(description="카탈로그 내부 id")
    song_id: str | None = Field(description="앱 songs.song_id (tj-번호). tj_id 로부터 유도")
    title: str
    artist: str
    metadata: TrackMetadata
    axis: Literal["ECAPA", "SINGER_BYOL", "CONSENSUS"] | None = Field(default=None, description="음색 섹션(voice)에서만. 두 축 할당 결과")
    label: str | None = Field(default=None, description="음색 섹션에서 화면에 그대로 표시하는 문구")
    axis_scores: AxisScores | None = None
    axis_rank: dict[str, int] | None = Field(default=None, description="후보군 안 축별 순위(1~). 로그용")
    confidence: float | None = Field(default=None, ge=0, le=1, description="해당 축의 query 구간 일치율. 로그·진단용")
    timbre_score: float = Field(ge=0, le=1)
    range_fit: RangeFit | None = Field(description="사용자 음역 프로파일이 없거나 신뢰도가 낮으면 null")
    recommended_key: int | None = Field(description="반음. range_fit 이 null 이면 null")
    final_score: float = Field(ge=0, le=1, description="서버 최종 점수. 클라이언트 계산 금지")
    metrics: dict[str, Any] = Field(default_factory=dict, description="섹션별 표시 지표 (coverage0, center0, artist_similarity, high_ratio0 …)")
    scoring_version: str
    composition_version: str


class Shelf(BaseModel):
    """추천 섹션 하나. 섹션 간 같은 곡은 없다."""

    model_config = ConfigDict(extra="allow")

    key: Literal["voice", "original_key", "favorite_artists", "challenge", "discovery"]
    title: str
    metric: str = Field(description="이 섹션이 어떤 지표로 뽑혔는지 한 줄 (화면 부제로 그대로 사용 가능)")
    items: list[Recommendation]


class LanguageGate(BaseModel):
    enabled: bool
    genres: list[str]
    allowed: list[str] | None
    excluded_count: int | None = None


class Algorithm(BaseModel):
    """알려지지 않은 필드는 통과."""

    model_config = ConfigDict(extra="allow")

    scoring_version: str
    shelves_version: str
    applied_mode: str
    range_enabled: bool
    range_fallback_reason: str | None = None
    language_gate: LanguageGate
    preferred_artists: list[str] = []


class RecommendResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    algorithm: Algorithm
    query: dict[str, Any]
    shelves: list[Shelf]
    recommendations: list[Recommendation] = Field(default_factory=list, description="shelves[0].items 복사본 (호환용)")
    catalog_track_count: int
    limitations: list[str] = []


class Job(BaseModel):
    """POST /recommendations(202) 와 GET /recommendations/{id} 의 응답."""

    model_config = ConfigDict(extra="allow")

    id: str
    status: Literal["queued", "processing", "complete", "failed"]
    progress: int = Field(ge=0, le=100)
    message: str
    mode: str
    genres: list[str] = []
    preferred_artists: list[str] = []
    result: RecommendResult | None = None
    error: str | None = None
