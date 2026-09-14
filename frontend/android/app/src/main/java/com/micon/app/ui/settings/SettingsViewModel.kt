package com.micon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.local.UserStore
import com.micon.app.data.settings.SettingsRepository
import com.micon.app.ui.common.describeBackendError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 설정 화면의 서버 상태. "원본 녹음 저장" 토글은 `GET/PATCH /users/me/settings`(낙관적 반영, 실패 시 되돌림),
 * "음성데이터 삭제" 는 `POST /users/me/voice-data-deletions` 뒤 로컬 음성 프로필·마지막 녹음도 비운다.
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    data class State(
        /** 서버 값. 아직 못 읽었으면 null(토글 비활성) */
        val saveOriginalRecording: Boolean? = null,
        val settingsError: String? = null,
        val saving: Boolean = false,
        val deleting: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(saveOriginalRecording = settings.settings().saveOriginalRecording, settingsError = null) }
            } catch (e: Exception) {
                _state.update { it.copy(settingsError = describeBackendError(e, "설정을 불러오지 못했어요.")) }
            }
        }
    }

    fun setSaveOriginalRecording(enabled: Boolean) {
        val previous = _state.value.saveOriginalRecording ?: return
        if (_state.value.saving || previous == enabled) return
        _state.update { it.copy(saveOriginalRecording = enabled, saving = true) }
        viewModelScope.launch {
            try {
                val saved = settings.updateSaveOriginalRecording(enabled)
                _state.update { it.copy(saveOriginalRecording = saved.saveOriginalRecording, saving = false) }
            } catch (e: Exception) {
                _state.update { it.copy(saveOriginalRecording = previous, saving = false, toast = describeBackendError(e, "설정을 저장하지 못했어요.")) }
            }
        }
    }

    fun deleteVoiceData() {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                val result = settings.deleteVoiceData()
                // 서버 기록이 사라졌으므로 리포트 탭이 보여 주던 로컬 프로필도 비운다.
                UserStore.setProfile(null)
                val message = if (result.isCompleted) {
                    "음성 데이터를 삭제했어요 (녹음 ${result.deletedRecordingCount}건 · 분석 ${result.deletedAnalysisCount}건)."
                } else {
                    "삭제를 요청했어요. 서버에서 처리 중이에요."
                }
                _state.update { it.copy(deleting = false, toast = message) }
            } catch (e: Exception) {
                _state.update { it.copy(deleting = false, toast = describeBackendError(e, "음성 데이터를 삭제하지 못했어요.")) }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
