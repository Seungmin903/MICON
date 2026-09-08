package com.micon.app.data

/**
 * 추천·적합도 접근 인터페이스 (REC-001~007, FIT-001~006). 화면·ViewModel 은 이 인터페이스만 본다.
 *
 * 현재 구현: [com.micon.app.data.local.LocalRecommendRepository] (sqlite 곡 데이터 + UserStore 프로필로 앱 안에서 계산).
 * 백엔드 추천 API 가 생기면 Retrofit 구현으로 교체한다.
 */
interface RecommendRepository {

    /**
     * 곡 하나의 적합도. 추천 목록에 없는 곡(검색·연습 탭·찜에서 진입)도 여기로 조회한다.
     * `null` = 평가 불가 — 곡 음역 정보가 없거나(ERR-004) 사용자 음성 프로필이 없을 때.
     */
    suspend fun fit(songId: String): SongFit?

    /**
     * 추천 목록. **순서·비율은 구현(서버)이 정한 그대로** 돌려주고 화면은 재정렬하지 않는다(REC-001).
     * 프로필이 없거나 [mode] 를 지원할 데이터가 없으면 개인화하지 않은 기본 추천을 돌려준다(REC-004).
     */
    suspend fun recommend(mode: RecommendMode, limit: Int = 10): RecommendResult
}

/** 추천 화면의 필터 칩과 1:1. 음역대·음색은 개인화, 장르·인기는 기본 추천 기준. */
enum class RecommendMode { RANGE, TIMBRE, GENRE, POPULAR }

data class RecommendResult(
    val mode: RecommendMode,
    /** false 면 화면에 "기본 추천"으로 표시하고 개인화 문구를 쓰지 않는다(REC-004). */
    val personalized: Boolean,
    val items: List<RecommendedSong>,
    /** 서버 `algorithm.language_gate.allowed` (ko/jp/en). 참고 표시용 — 클라이언트에서 다시 필터하지 않는다. 로컬은 null */
    val languageGateAllowed: List<String>? = null,
)

data class RecommendedSong(
    /** 1부터 */
    val rank: Int,
    val song: SongSummary,
    /** 기본 추천이면 null */
    val fit: SongFit?,
    /** 추천 사유. 개인화면 fit.reasons 와 같고, 기본 추천이면 "선호 장르: 발라드" 식. 최소 1개(REC-002) */
    val reasons: List<String>,
)
