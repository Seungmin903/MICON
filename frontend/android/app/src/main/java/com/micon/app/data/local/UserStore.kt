package com.micon.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 사용자 쪽 데이터의 **임시 인메모리 저장소**(음성 프로필·닉네임·온보딩 선호·마지막 녹음).
 * 찜·세트리스트·연습 기록은 백엔드 Repository(`FavoriteRepository`·`SetlistRepository`·`PracticeRepository`)로 옮겼다.
 * 음성 프로필은 서버 분석 결과(`VoiceReportViewModel`)가 덮어쓴다. 남은 항목도 서버로 옮기면 이 객체는 삭제한다.
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
        val voiceQuality: com.micon.app.range.VoiceQualityResult? = null,
        val bpmRange: String = "72-96",
        val phraseLevel: Int = 3,         // 7 단계 중
        val genres: List<String> = listOf("발라드", "어쿠스틱", "감성적인", "R&B"),
        /** 종합 보컬 특성: 음역·음정·고음·호흡·리듬·발음 (0~1) */
        val traits: List<Float> = listOf(0.9f, 0.75f, 0.55f, 0.6f, 0.5f, 0.7f),
        val traitLabels: List<String> = listOf("음역", "음정", "고음", "호흡", "리듬", "발음"),
        val strengths: List<String> = listOf("음정 안정", "호흡 강점"),
        val measuredAt: Long = System.currentTimeMillis(),
    )

    private val _profile = MutableStateFlow<VoiceProfile?>(null)
    val profile = _profile.asStateFlow()
    private val _voiceDataRevision = MutableStateFlow(0L)
    val voiceDataRevision = _voiceDataRevision.asStateFlow()

    /** 저장된 프로필을 서버·기기에서 복원하다 실패했다. profile 이 null 이어도 "미측정" 이 아니라 "불러오지 못함" 으로 보여야 한다. */
    private val _profileLoadFailed = MutableStateFlow(false)
    val profileLoadFailed = _profileLoadFailed.asStateFlow()

    /** 화면에 부르는 이름 ("유림님 음색에 딱인 곡"). 백엔드 users API 가 생기면 로그인 응답에서 채운다. null 이면 "내 …" 로 쓴다. */
    var nickname: String? = null

    /** 온보딩 선호 조사 결과 */
    var preferredArtists: Set<String> = emptySet()
    var preferredGenres: Set<String> = emptySet()

    /** Clear account-scoped temporary state before another account can use the app. */
    fun clearUserData() {
        clearVoiceData()
        nickname = null
        preferredArtists = emptySet()
        preferredGenres = emptySet()
    }

    @Synchronized fun setProfile(profile: VoiceProfile?) {
        val changed = _profile.value != profile
        _profile.value = profile
        _profileLoadFailed.value = false
        if (changed) _voiceDataRevision.value++
    }
    @Synchronized fun clearVoiceData() {
        _profile.value = null
        _profileLoadFailed.value = false
        _voiceDataRevision.value++
    }
    fun setProfileLoadFailed(failed: Boolean) { _profileLoadFailed.value = failed }

    // 추천 키·적합도는 RecommendRepository(LocalRecommendRepository) 가 계산한다. 임시 recommendedKey() 는 제거했다.
}
