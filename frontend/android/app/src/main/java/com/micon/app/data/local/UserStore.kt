package com.micon.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 사용자 쪽 데이터의 **임시 인메모리 저장소**. 앱 백엔드(users, favorites, setlists, practice_sessions,
 * user_voice_profiles, song_fit) API 가 생기면 Repository 로 바꾸고 이 객체는 삭제한다.
 * 화면은 여기의 StateFlow 만 구독하므로 교체 시 UI 수정은 없다.
 */
object UserStore {

    /** 음성 분석 리포트 (user_voice_profiles). 간편 분석이 끝나면 채워진다. */
    data class VoiceProfile(
        val lowMidi: Int = 48,            // 전체 음역 C3
        val highMidi: Int = 72,           // C5
        val comfortLow: Int = 55,         // 편안한(안정) 음역 G3
        val comfortHigh: Int = 67,        // G4
        val recommendLow: Int = 57,       // 추천곡 음역 A3
        val recommendHigh: Int = 65,      // F4
        /** 음역별 안정성 0(부담)~1(안정). x 축은 stabilityLabels. */
        val stability: List<Float> = listOf(0f, 0.45f, 0.95f, 1f, 0.85f, 0.55f, 0.3f, 0f),
        val stabilityLabels: List<String> = listOf("G2", "C3", "F3", "B3", "E4", "A4", "D5", "G5"),
        val bpmRange: String = "72-96",
        val phraseLevel: Int = 3,         // 7 단계 중
        val genres: List<String> = listOf("발라드", "어쿠스틱", "감성적인", "R&B"),
        /** 종합 보컬 특성: 음역·음정·고음·호흡·리듬·발음 (0~1) */
        val traits: List<Float> = listOf(0.9f, 0.75f, 0.55f, 0.6f, 0.5f, 0.7f),
        val traitLabels: List<String> = listOf("음역", "음정", "고음", "호흡", "리듬", "발음"),
        val strengths: List<String> = listOf("음정 안정", "호흡 강점"),
        val measuredAt: Long = System.currentTimeMillis(),
    )

    data class Setlist(val id: String, val name: String, val songIds: List<String>)

    private val _profile = MutableStateFlow<VoiceProfile?>(null)
    val profile = _profile.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    val liked = _liked.asStateFlow()

    private val _setlists = MutableStateFlow(
        listOf(
            Setlist("sl-1", "잔잔한 노래 모음", emptyList()),
            Setlist("sl-2", "회식용", emptyList()),
        ),
    )
    val setlists = _setlists.asStateFlow()

    /** 온보딩 선호 조사 결과 */
    var preferredArtists: Set<String> = emptySet()
    var preferredGenres: Set<String> = emptySet()

    /** Clear account-scoped temporary state before another account can use the app. */
    fun clearUserData() {
        _profile.value = null
        _liked.value = emptySet()
        _setlists.value = emptyList()
        preferredArtists = emptySet()
        preferredGenres = emptySet()
    }

    fun setProfile(profile: VoiceProfile?) { _profile.value = profile }

    fun toggleLike(songId: String) = _liked.update { if (songId in it) it - songId else it + songId }

    /** 최초 진입 시 목록 화면이 호출해 예시 찜을 채운다(백엔드 연동 후 삭제). */
    fun seedLikesIfEmpty(songIds: List<String>) {
        if (_liked.value.isEmpty() && songIds.isNotEmpty()) {
            _liked.value = songIds.take(5).toSet()
            _setlists.update { lists -> lists.map { if (it.songIds.isEmpty()) it.copy(songIds = songIds.take(5)) else it } }
        }
    }

    fun addSetlist(name: String) = _setlists.update { it + Setlist("sl-${System.currentTimeMillis()}", name, emptyList()) }

    /**
     * 추천 키(song_fit.recommended_key) 임시값. 트레이너 답변 "최고음보다 3키 낮게" 후보를 흉내낸다.
     * 곡 최고음이 없으면 id 해시로 0/+1/+2/없음 중 하나를 준다(목록 배지 예시용).
     */
    fun recommendedKey(songId: String, songHighMidi: Int? = null): Int? {
        val p = _profile.value
        if (songHighMidi != null && p != null) return (p.comfortHigh - songHighMidi + 3).coerceIn(-6, 5)
        return when (Math.floorMod(songId.hashCode(), 4)) { 0 -> 0; 1 -> 1; 2 -> 2; else -> null }
    }
}
