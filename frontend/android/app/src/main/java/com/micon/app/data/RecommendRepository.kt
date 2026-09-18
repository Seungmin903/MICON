package com.micon.app.data

/**
 * 추천·적합도 접근 인터페이스 (REC-001~007, FIT-001~006). 화면·ViewModel 은 이 인터페이스만 본다.
 *
 * 구현: [com.micon.app.data.recommend.BackendRecommendRepository] (백엔드 `/recommendations`·`/songs/{id}/fit`) —
 * 서버 추천이 없을 때는 [com.micon.app.data.local.LocalRecommendRepository] (곡 데이터 + UserStore 프로필로 앱 안 음역 계산)로 내려간다.
 */
interface RecommendRepository {

    /**
     * 곡 하나의 적합도. 추천 목록에 없는 곡(검색·연습 탭·찜에서 진입)도 여기로 조회한다.
     * `null` = 평가 불가 — 곡 음역 정보가 없거나(ERR-004) 사용자 음성 프로필이 없을 때.
     */
    suspend fun fit(songId: String): SongFit?

    /**
     * 추천 섹션 목록(shelves-v1) — 추천 탭이 좌우로 넘기는 페이지 하나가 섹션 하나. 순서·곡은 구현(서버)이 정한 그대로(REC-001).
     * 고음 도전(`challenge`)은 화면에서 쓰지 않으므로 구현이 빼고 준다.
     * 추천할 근거(로그인·목소리 분석)가 없으면 **빈 목록** 과 그 이유([RecommendShelves.note])를 돌려준다 — 기본 추천은 없다.
     *
     * [reshuffle] 은 "새로운 발견" 다시 뽑기 횟수. 이 섹션만 무작위(하루 단위)라 값이 바뀌면 다른 순서가 나온다.
     */
    suspend fun shelves(limit: Int = 10, reshuffle: Int = 0): RecommendShelves

    /**
     * 서버 추천을 새로 만들어 달라고 요청한다. 결과가 비었을 때(REC EMPTY) 사용자가 눌러서만 부른다 — 화면을 열 때마다 자동으로 다시 만들지 않는다.
     * true = 요청이 접수됐거나 이미 만드는 중. 서버 추천이 없는 구현은 false.
     */
    suspend fun regenerate(): Boolean = false
}

data class RecommendShelves(
    /** false 면 개인화 문구(이름·"내 음색")를 쓰지 않는다. */
    val personalized: Boolean,
    val shelves: List<RecommendShelf>,
    /** 서버 추천 대신 기기 계산을 보여 주는 이유(생성 중·미준비 등), 또는 [shelves] 가 빈 이유. null 이면 안내 없음. 화면이 그대로 보인다. */
    val note: String? = null,
) {
    companion object {
        const val NOTE_LOGIN_REQUIRED = "로그인하면 내 목소리에 맞춘 추천을 받을 수 있어요."
        const val NOTE_ANALYSIS_REQUIRED = "목소리 분석을 마쳐야 추천을 받을 수 있어요."
        /** 서버가 추천을 끝까지 만들었지만 곡이 0개였다. 화면은 이 문구로 "다시 만들기" 를 붙인다. */
        const val NOTE_EMPTY = "서버 추천 결과가 비어 있어요."

        fun empty(note: String) = RecommendShelves(personalized = false, shelves = emptyList(), note = note)
    }
}

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

data class RecommendedSong(
    /** 1부터 */
    val rank: Int,
    val song: SongSummary,
    /** 검색 결과처럼 적합도가 없으면 null */
    val fit: SongFit?,
    /** 추천 사유. 개인화면 fit.reasons 와 같다. 최소 1개(REC-002) */
    val reasons: List<String>,
)
