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

    /**
     * 추천 섹션 목록(shelves-v1) — 추천 탭이 좌우로 넘기는 페이지 하나가 섹션 하나. 순서·곡은 구현(서버)이 정한 그대로.
     * 고음 도전(`challenge`)은 화면에서 쓰지 않으므로 구현이 빼고 준다.
     *
     * [reshuffle] 은 "새로운 발견" 다시 뽑기 횟수. 이 섹션만 무작위(하루 단위)라 값이 바뀌면 다른 순서가 나온다.
     */
    suspend fun shelves(limit: Int = 10, reshuffle: Int = 0): RecommendShelves
}

/** 이전 모드 기반 API 용. 추천 탭은 [RecommendRepository.shelves] 를 쓴다. */
enum class RecommendMode { RANGE, TIMBRE, GENRE, POPULAR }

data class RecommendShelves(
    /** false 면 "기본 추천"으로 표시하고 개인화 문구를 쓰지 않는다(REC-004). */
    val personalized: Boolean,
    val shelves: List<RecommendShelf>,
)

/** 섹션 하나. [key] 는 서버 문자열 그대로([ShelfKeys]). 제목·설명(metric)도 서버 문구를 그대로 보인다. */
data class RecommendShelf(
    val key: String,
    val title: String,
    val metric: String?,
    val items: List<RecommendedSong>,
)

object ShelfKeys {
    const val VOICE = "voice"
    const val ORIGINAL_KEY = "original_key"
    const val FAVORITE_ARTISTS = "favorite_artists"
    const val CHALLENGE = "challenge"
    const val DISCOVERY = "discovery"
}

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
