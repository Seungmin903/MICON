package com.micon.app.data

/**
 * 사용자 × 곡 적합도 (FIT-001~006). 화면은 이 값을 **그리기만** 하고 계산·재정렬하지 않는다.
 *
 * 채우는 곳 둘: `LocalRecommendRepository`(음역만, 오프라인) / `RemoteRecommendRepository`(서버 `compose_two_axis` 응답 매핑).
 * 서버 응답 DTO 는 별도(`data/recommend/dto`)로 1:1 유지하고, 이 클래스는 앱 용어로 둔다.
 * 제목·가수는 [SongSummary] 가 이미 가지고 있어 여기 두지 않는다.
 *
 * `null` 은 0점이 아니라 **평가 불가**(데이터 없음)다 — 화면에서 반드시 구분해 표시한다(FIT-002·003, ANL-008).
 */
data class SongFit(
    /** = 서버 track_id = songs.song_id (tj-번호) */
    val songId: String,
    /** 음색 — 두 축 + 라벨. 서버 응답에만 있고 로컬 계산은 null. FIT-002 */
    val timbre: TimbreMatch?,
    /** 음색 적합도 단일 숫자 0~1. 현재 서버는 라벨만 내려주므로 null. 숫자 표시가 결정되면 채운다. FIT-002 */
    val timbreFit: Float? = null,
    /** 리듬 적합도 0~1. 데이터원이 아직 없어 항상 null = 평가 불가. FIT-003 */
    val rhythmFit: Float? = null,
    /** 음역 적합도 상세. 사용자 프로필이 없으면 null. FIT-001 */
    val rangeFit: RangeFit?,
    /** 추천 키 (반음, 음수 = 내림). rangeFit 이 null 이면 null. FIT-005 */
    val recommendedKey: Int?,
    /** 원키 / 추천 키 적용 시 부담 구간. FIT-005·006 */
    val strainSections: StrainSections?,
    /** 종합 적합도 0~1. 서버 `final_score` 그대로. 로컬은 rangeFit.score 를 넣는다. TODO(§15 미확정): 종합 공식 확정 시 로컬 계산 교체. FIT-004 */
    val overallFit: Float,
    /** 추천 사유. 음역 사유(로컬 규칙) + 음색 라벨(서버). 최소 1개. REC-002 */
    val reasons: List<String>,
    /** 점수 규칙 버전 (서버 `scoring_version`, 로컬은 RangeFit 상수) */
    val scoringVersion: String,
    /** 서버 배치 규칙 버전 (`composition_version`, 예 "two-axis-quota-v1"). 로컬은 null */
    val compositionVersion: String?,
)

/** 서버가 고른 음색 축과 사용자에게 보여 줄 라벨. 어느 라벨을 고르는지가 개인화 데이터가 된다(RecommendEvent). */
data class TimbreMatch(
    val axis: TimbreAxis,
    /** 서버 문구 그대로 표시. 예 "목소리 물성이 가까운 가수" */
    val label: String,
    /** 로그·디버그용. 화면에 표시하지 않는다. 서버가 생략하면 null */
    val axisScores: AxisScores?,
)

/** ECAPA = 목소리 물성, SINGER_BYOL = 부르는 방식, CONSENSUS = 두 축 합의 */
enum class TimbreAxis { ECAPA, SINGER_BYOL, CONSENSUS }

/** 축별 0~1 백분위 */
data class AxisScores(val ecapa: Float, val singerByol: Float, val consensus: Float)

/** 음역 적합도 상세 (recommend-algo `vocal-range-keyshift-continuous-v2` 항목과 같은 의미) */
data class RangeFit(
    /** 최종 음역 점수 0~1 */
    val score: Float,
    /** 곡 음표 중 내 안정 음역(±2반음) 안에 든 비율 */
    val coverage: Float,
    /** 곡 중심음과 내 중심음의 근접도 */
    val centerScore: Float,
    /** 곡 음역 폭이 내 폭을 넘지 않는 정도 */
    val spanFit: Float,
    /** 커버리지 sigmoid × 경계 초과 감점 */
    val softFit: Float,
    /** 곡 주요 음역(5~95 %). 로컬 카탈로그에 분석이 없는 곡(서버 추천)도 상세에서 음역 막대를 그릴 수 있게 */
    val songCoreLowMidi: Int? = null,
    val songCoreHighMidi: Int? = null,
)

data class StrainSections(
    /** 원키로 불렀을 때 */
    val original: List<StrainSection>,
    /** 추천 키 적용 시. 원키와 비교해 "적용 시 변화"를 보여 준다. */
    val atKey: List<StrainSection>,
)

/** 사용자 안정 음역을 벗어나는 연속 구간. 시간은 곡 기준 초. */
data class StrainSection(
    val startSec: Double,
    val endSec: Double,
    /** 구간 안에서 안정 음역 상한을 넘은 최대 반음 수 */
    val maxExcessSemitones: Int,
)
