package com.micon.app.data.settings

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.SettingUpdateRequest
import com.micon.app.data.backend.dto.VoiceDataDeletionRequest
import com.micon.app.data.backend.unwrapData

/** 사용자 설정(`SettingResponse`). [saveOriginalRecording] 은 분석 뒤 원본 녹음 보관 여부 — 서버 업로드가 keepOriginal 미지정 시 이 값을 쓴다. */
data class UserSettings(val saveOriginalRecording: Boolean)

/** 음성 데이터 일괄 삭제 결과(`VoiceDataDeletionResponse.Result`). */
data class VoiceDataDeletionResult(
    val deletionId: Long,
    /** COMPLETED | PROCESSING */
    val status: String,
    val deletedAnalysisCount: Int,
    val deletedRecordingCount: Int,
) {
    val isCompleted: Boolean get() = status == "COMPLETED"
}

/**
 * 설정 화면의 서버 연동. 백엔드 `SettingController`(`/users/me/settings`)·`VoiceDataDeletionController`(`/users/me/voice-data-deletions`)만 본다.
 * 모두 로그인 필요(비회원 401 → AuthSessionExpiredException).
 */
interface SettingsRepository {
    suspend fun settings(): UserSettings
    suspend fun updateSaveOriginalRecording(enabled: Boolean): UserSettings
    /** 내 녹음·분석을 모두 지운다(진행 중 분석은 서버가 거절, 409 VOICE_ANALYSIS_IN_PROGRESS). */
    suspend fun deleteVoiceData(): VoiceDataDeletionResult
}

class RemoteSettingsRepository(private val api: BackendApi) : SettingsRepository {
    override suspend fun settings(): UserSettings = UserSettings(api.settings().unwrapData().saveOriginalRecording)

    override suspend fun updateSaveOriginalRecording(enabled: Boolean): UserSettings =
        UserSettings(api.updateSettings(SettingUpdateRequest(enabled)).unwrapData().saveOriginalRecording)

    override suspend fun deleteVoiceData(): VoiceDataDeletionResult {
        val dto = api.deleteVoiceData(VoiceDataDeletionRequest(confirmed = true)).unwrapData()
        return VoiceDataDeletionResult(dto.deletionId, dto.status ?: "UNKNOWN", dto.deletedAnalysisCount, dto.deletedRecordingCount)
    }
}
