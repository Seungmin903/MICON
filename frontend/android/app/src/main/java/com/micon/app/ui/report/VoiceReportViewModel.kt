package com.micon.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.local.UserStore
import com.micon.app.data.voice.VoiceAnalysis
import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceAnalysisStatus
import com.micon.app.data.voice.VoiceApiException
import com.micon.app.data.voice.VoiceProfileMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 리포트 탭의 서버 상태. 로그인 사용자는 `GET /voice-analyses/latest` 로 최근 분석을 보고,
 * COMPLETED 면 리포트를 받아 [UserStore.setProfile] 로 카드에 반영한다(간편 분석의 임시 프로필을 서버 결과가 덮는다).
 * 비회원은 서버를 부르지 않는다.
 */
class VoiceReportViewModel(
    private val voice: VoiceAnalysisRepository,
    private val loggedIn: Boolean,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = false,
        /** 가장 최근 서버 분석. 없으면 null */
        val latest: VoiceAnalysis? = null,
        /** 최근 분석 결과를 프로필에 반영했는가 */
        val appliedAnalysisId: Long? = null,
        /** 서버 조회 실패 문구(짧게). 프로필 표시는 막지 않는다 */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun refresh() {
        if (!loggedIn || _state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val latest = voice.latest()
                var applied = _state.value.appliedAnalysisId
                if (latest != null && latest.status == VoiceAnalysisStatus.COMPLETED && applied != latest.id) {
                    val profile = VoiceProfileMapper.toProfile(voice.report(latest.id))
                    if (profile != null) { UserStore.setProfile(profile); applied = latest.id }
                }
                _state.update { it.copy(loading = false, latest = latest, appliedAnalysisId = applied) }
            } catch (e: VoiceApiException) {
                _state.update { it.copy(loading = false, error = if (e.status == 401) null else e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = "서버에 연결할 수 없어 최근 분석을 확인하지 못했어요.") }
            }
        }
    }
}
