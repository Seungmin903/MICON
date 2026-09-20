package com.micon.app.data.backend.dto

data class VoiceSessionDto(
    val sessionId: Long, val guestSessionToken: String? = null, val status: String,
    val rangeStatus: String, val timbreStatus: String, val artistSearchStatus: String,
    val resultVersion: Long, val savedProfileExists: Boolean, val recoverable: Boolean,
    val expiresAt: String, val range: VoiceRangeDto? = null,
)
data class VoiceRangeDto(val lowMidi: Double?, val highMidi: Double?, val measurementStatus: String, val qualityStatus: String,
    val noteResults: List<com.micon.app.range.RangeNote>? = null)
data class DeviceRangeResultRequest(
    val measurementId: String, val measuredAt: String, val protocolVersion: String,
    val lowMidi: Double?, val highMidi: Double?, val noteResults: List<DeviceRangeNoteDto>,
    val measurementStatus: String, val qualityStatus: String,
)
data class DeviceRangeNoteDto(val midi: Int, val status: String, val stability: Double? = null, val holdMs: Int? = null)
data class VoiceTimbreMetaRequest(
    val requestId: String, val method: String = "SONG", val songId: String,
    val guideVersion: String, val recordedAt: String, val durationMs: Int, val sampleRate: Int,
    val startPositionType: String, val selectedStartMs: Int, val actualStartMs: Int,
    val instrumentalVersion: String, val lyricsVersion: String, val segments: List<VoiceSegmentDto>,
)
data class VoiceSegmentDto(val recordingStartMs: Int, val recordingEndMs: Int, val songStartMs: Int, val songEndMs: Int)
data class VoiceJobDto(
    val analysisId: Long?, val sessionId: Long?, val status: String,
    val failureCode: String? = null, val failureReason: String? = null,
    val reuploadRequired: Boolean = false, val components: List<VoiceComponentDto> = emptyList(),
)
data class VoiceComponentDto(val component: String, val status: String, val failureCode: String? = null, val failureReason: String? = null)
data class VoiceOnboardingResultDto(
    val sessionId: Long, val status: String, val resultVersion: Long,
    val rangeStatus: String, val timbreStatus: String, val saveAllowed: Boolean,
    val savedProfileExists: Boolean, val expiresAt: String, val artistSearchStatus: String = "PENDING", val range: VoiceRangeDto? = null,
    val components: List<VoiceComponentDto> = emptyList(), val artists: List<VoiceArtistDto> = emptyList(),
)
data class VoiceArtistDto(val artistId: Long, val name: String, val imageUrl: String? = null, val similarity: Double)
data class VoiceProfileSaveRequest(val resultVersion: Long)
data class VoiceProfileDto(val profileId: Long, val sessionId: Long, val profileVersion: Long)
