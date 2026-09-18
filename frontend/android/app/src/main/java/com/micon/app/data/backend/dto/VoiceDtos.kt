package com.micon.app.data.backend.dto

/*
 * 백엔드 음성 녹음·분석 계약 (backend/src/main/java/com/micon/backend/voice/api).
 * 필드명은 서버 record 그대로. BigDecimal 은 Double, JsonNode 는 Any(Moshi 가 Map/List/Double/String 으로 읽는다),
 * LocalDateTime 은 ISO 문자열("2026-09-13T21:00:00")로 온다. 모두 기본값을 둬 리플렉션 어댑터가 부분 응답도 읽게 한다.
 */

/** `POST /recordings` → RecordingController.RecordingUploadResponse */
data class RecordingUploadDto(val recordingId: Long = 0)

/** `POST /voice-analyses` 요청. method = SCALE(간편 분석) | SONG(불러서 분석하기) */
data class VoiceAnalysisCreateRequest(val recordingId: Long, val method: String, val songId: String?)

/** VoiceAnalysisResponse 와 VoiceAnalysisHistoryResponse.Summary 의 공통 필드(둘은 analysisVersion 유무만 다르다). */
data class VoiceAnalysisDto(
    val analysisId: Long = 0,
    val recordingId: Long? = null,
    val songId: String? = null,
    val method: String? = null,
    /** READY·RECORDING·UPLOADING·ANALYZING·COMPLETED·FAILED·CANCELED·INSUFFICIENT_DATA */
    val status: String? = null,
    /** VALID·NOISY·CLIPPED·INSUFFICIENT_VOICE */
    val qualityStatus: String? = null,
    val failureCode: String? = null,
    val failureReason: String? = null,
    val analysisVersion: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val createdAt: String? = null,
)

/** `GET /voice-analyses/{id}/report` → VoiceAnalysisReportResponse. range·timbre 는 해당 분석 행이 없으면 null. */
data class VoiceAnalysisReportDto(
    val analysis: VoiceAnalysisDto? = null,
    val range: VocalRangeDto? = null,
    val timbre: VocalTimbreDto? = null,
)

data class VocalRangeDto(
    val fullLowMidi: Double? = null,
    val fullHighMidi: Double? = null,
    val stableLowMidi: Double? = null,
    val stableHighMidi: Double? = null,
    val pitchMedianMidi: Double? = null,
    val pitchAccuracyScore: Double? = null,
    val pitchStabilityScore: Double? = null,
    val pitchDistribution: Any? = null,
    val modalLowMidi: Double? = null,
    val modalHighMidi: Double? = null,
    val falsettoLowMidi: Double? = null,
    val falsettoHighMidi: Double? = null,
    val highNoteHoldSec: Double? = null,
    val confidence: Double? = null,
    val noteResults: Any? = null,
    val measuredAt: String? = null,
)

data class VocalTimbreDto(
    val timbreLabel: String? = null,
    val rhythmScore: Double? = null,
    val pronunciationScore: Double? = null,
    val confidence: Double? = null,
    val explanation: String? = null,
    val validSingleSec: Double? = null,
    val validChunkCount: Int? = null,
    val chunkCohesion: Double? = null,
    val observedCoreLowMidi: Double? = null,
    val observedCoreHighMidi: Double? = null,
    val observedMedianMidi: Double? = null,
    val pitchHistogram: Any? = null,
    val measuredAt: String? = null,
)
