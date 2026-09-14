package com.micon.app.data.backend.dto

/*
 * 백엔드 설정·음성 데이터 삭제 계약 (backend/src/main/java/com/micon/backend/setting/api, voice/api/VoiceDataDeletion*).
 */

/** `GET/PATCH /users/me/settings` 응답 → SettingResponse */
data class SettingResponseDto(val saveOriginalRecording: Boolean = false)

/** `PATCH /users/me/settings` → SettingRequest.Update */
data class SettingUpdateRequest(val saveOriginalRecording: Boolean)

/** `POST /users/me/voice-data-deletions` → VoiceDataDeletionRequest. confirmed 는 true 여야 한다(400). */
data class VoiceDataDeletionRequest(val confirmed: Boolean = true)

/** VoiceDataDeletionResponse.Result. status: COMPLETED | PROCESSING */
data class VoiceDataDeletionResultDto(
    val deletionId: Long = 0,
    val status: String? = null,
    val deletedAnalysisCount: Int = 0,
    val deletedRecordingCount: Int = 0,
    val requestedAt: String? = null,
    val completedAt: String? = null,
)
