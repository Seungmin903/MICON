"""추천 응답 스키마 (pydantic). 이 파일이 BE·FE 계약의 원본이다.

`docs/response-schema.json`(JSON Schema)과 `docs/sample-response.json` 은 `scripts/make_sample_response.py` 가 여기서 만든다.
필드를 바꾸면 그 스크립트를 다시 돌려 두 문서를 갱신한다.
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


class Recommendation(BaseModel):
    position: int = Field(ge=1, description="목록 위치. 서버가 정한 순서이며 클라이언트는 재정렬하지 않는다")
    track_id: str = Field(description="카탈로그 내부 id")
    song_id: str | None = Field(description="앱 songs.song_id (tj-번호). tj_id 로부터 유도")
    title: str
    artist: str
    metadata: TrackMetadata
    axis: Literal["ECAPA", "SINGER_BYOL", "CONSENSUS"]
    label: str = Field(description="화면에 그대로 표시하는 문구")
    axis_scores: AxisScores
    axis_rank: dict[str, int] = Field(description="후보군 안 축별 순위(1~). 로그용")
    confidence: float = Field(ge=0, le=1, description="해당 축의 query 구간 일치율. 로그·진단용, 순서에 쓰지 않음")
    timbre_score: float = Field(ge=0, le=1)
    range_fit: RangeFit | None = Field(description="사용자 음역 프로파일이 없거나 신뢰도가 낮으면 null")
    recommended_key: int | None = Field(description="반음. range_fit 이 null 이면 null")
    final_score: float = Field(ge=0, le=1, description="서버 최종 점수. 클라이언트 계산 금지")
    scoring_version: str
    composition_version: str


class Composition(BaseModel):
    version: str
    quota: dict[str, int] = Field(description="축별 할당 수")
    filled: dict[str, int] = Field(description="실제 채운 수. consensus 가 모자라면 다른 축이 채움")
    consensus_top_n: int
    order: str = Field(description="배치 순서 규칙. final_score desc")
    candidate_count: int


class LanguageGate(BaseModel):
    enabled: bool
    genres: list[str]
    allowed: list[str] | None
    excluded_count: int


class RangeFilter(BaseModel):
    enabled: bool
    min_score: float
    excluded_count: int


class Algorithm(BaseModel):
    """scoring.recommend() 의 algorithm 블록 + 배치·게이트. 알려지지 않은 필드는 통과."""

    model_config = ConfigDict(extra="allow")

    scoring_version: str
    range_scoring_version: str | None = None
    requested_mode: str
    applied_mode: str
    composition: Composition
    language_gate: LanguageGate
    range_filter: RangeFilter
    catalog_filter: dict[str, int]


class RecommendResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    algorithm: Algorithm
    query: dict[str, Any]
    recommendations: list[Recommendation]
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
    result: RecommendResult | None = None
    error: str | None = None
