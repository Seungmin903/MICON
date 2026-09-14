package com.micon.app.data.backend.dto

/*
 * 백엔드 연습 기록 계약 (backend/src/main/java/com/micon/backend/practice/api).
 * 필드명은 서버 record 그대로. BigDecimal 은 Double, LocalDateTime 은 ISO 문자열("2026-09-13T21:00:00")로 온다.
 * 응답 DTO 는 기본값을 둬 리플렉션 어댑터가 부분 응답도 읽게 한다.
 */

/** `POST /practices` → PracticeRequest.Create. practiceScope: FULL_SONG | SECTION | CUSTOM */
data class PracticeCreateRequest(
    val songId: Long,
    val selectedKey: Int,
    val practiceScope: String = "FULL_SONG",
    val recordingId: Long? = null,
    val sectionId: Long? = null,
    val playbackSpeed: Double? = null,
    val customStartMs: Int? = null,
    val customEndMs: Int? = null,
)

/** `PATCH /practices/{id}/status` → PracticeRequest.StatusUpdate. status: RECORDING | IN_PROGRESS | PAUSED */
data class PracticeStatusRequest(val status: String)

/** `POST /practices/{id}/complete` → PracticeRequest.Complete */
data class PracticeCompleteRequest(val durationMs: Int, val voiceAnalysisId: Long? = null, val recordingId: Long? = null)

/** `PUT /practices/{id}/performance` → PracticeResultRequest.Save. 점수는 0~100, summaryText ≤ 500자, sections ≤ 20개 */
data class PracticeResultSaveRequest(
    /** COMPLETED | INSUFFICIENT_DATA | FAILED. COMPLETED 면 overall·pitch·rhythm 필수 */
    val status: String,
    val overallScore: Double?,
    val pitchScore: Double?,
    val sustainScore: Double?,
    val rhythmScore: Double?,
    val summaryText: String?,
    val sections: List<SectionEvaluationDto>,
)

/** PracticeResultRequest.SectionEvaluation — sectionId 는 해당 곡의 song_section 이어야 한다. summary ≤ 120자 */
data class SectionEvaluationDto(
    val sectionId: Long = 0,
    val score: Double? = null,
    val measured: Boolean = false,
    val summary: String? = null,
)

/** PracticeResponse.Detail */
data class PracticeDetailDto(
    val practiceId: Long = 0,
    val songId: Long = 0,
    val songTitle: String? = null,
    val sectionId: Long? = null,
    val recordingId: Long? = null,
    val voiceAnalysisId: Long? = null,
    val selectedKey: Int = 0,
    val playbackSpeed: Double? = null,
    val customStartMs: Int? = null,
    val customEndMs: Int? = null,
    val practiceScope: String? = null,
    /** READY·RECORDING·IN_PROGRESS·PAUSED·COMPLETED·ABORTED·FAILED */
    val status: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMs: Int? = null,
    val createdAt: String? = null,
)

/** PracticeHistoryResponse.Summary — `GET /practices` 목록 항목 */
data class PracticeSummaryDto(
    val practiceId: Long = 0,
    val songId: Long = 0,
    val songTitle: String? = null,
    val imageUrl: String? = null,
    val selectedKey: Int = 0,
    val practiceScope: String? = null,
    val sectionId: Long? = null,
    val status: String? = null,
    val startedAt: String? = null,
    val durationMs: Int? = null,
    /** 결과 행 상태(COMPLETED·INSUFFICIENT_DATA·FAILED), 결과가 없으면 null */
    val resultStatus: String? = null,
    val overallScore: Double? = null,
)

/** PracticeResultResponse.Report */
data class PracticeReportDto(
    val practiceId: Long = 0,
    val songId: Long = 0,
    val songTitle: String? = null,
    val selectedKey: Int = 0,
    val practiceScope: String? = null,
    val sectionId: Long? = null,
    val durationMs: Int? = null,
    val status: String? = null,
    val overallScore: Double? = null,
    val pitchScore: Double? = null,
    val sustainScore: Double? = null,
    val rhythmScore: Double? = null,
    val summaryText: String? = null,
    val sections: List<SectionEvaluationDto> = emptyList(),
    val weakSection: RetrySectionDto? = null,
    val analyzedAt: String? = null,
)

/** PracticeResultResponse.RetrySection */
data class RetrySectionDto(val sectionId: Long = 0, val selectedKey: Int = 0, val score: Double? = null)
