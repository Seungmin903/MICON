package com.micon.app.data.backend.dto

/*
 * 백엔드 연습 기록 계약 (backend/src/main/java/com/micon/backend/practice/api).
 * 필드명은 서버 record 그대로. BigDecimal 은 Double, LocalDateTime 은 ISO 문자열("2026-09-13T21:00:00")로 온다.
 * 응답 DTO 는 기본값을 둬 리플렉션 어댑터가 부분 응답도 읽게 한다.
 */

/** `POST /practices` → PracticeRequest.Create. practiceScope: FULL_SONG | SECTION | CUSTOM */
data class PracticeCreateRequest(
    val songId: String,
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

/**
 * `POST /practices/{id}/complete` → PracticeRequest.Complete.
 * [finalSelectedKey]·[keyChangeCount] 는 BE `fix/be/practiceSessionKey`(V29) 에서 추가된 선택 항목 — 실제로 부른 키와 변경 횟수.
 * 서버는 최종 키가 최초 키(세션 생성 키)와 다르면 변경 횟수 ≥ 1 을 요구하고, 그 키의 반주가 READY 인지도 검사한다.
 */
data class PracticeCompleteRequest(
    val durationMs: Int,
    val voiceAnalysisId: Long? = null,
    val recordingId: Long? = null,
    val finalSelectedKey: Int? = null,
    val keyChangeCount: Int? = null,
)

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
    // ---- 절충안 확장(2026-09-15): 기기 판정을 그대로 저장한다. 서버가 아직 받지 않으면 무시된다(Spring 은 모르는 필드를 버림). ----
    /** practice-assets 의 dataVersion — notes[].i 가 어느 음표 배열 기준인지 */
    val dataVersion: String? = null,
    /** 노트별 판정(판정된 음표만, 최대 600개) */
    val notes: List<PracticeNoteDto>? = null,
    /** 사용자 음정 곡선(100ms 간격) */
    val curve: PitchCurveDto? = null,
)

/**
 * 노트별 판정 한 개. [i] 는 practice-assets `targetNotes` 를 sourceStart 순으로 정렬한 인덱스(`NoteNormalizer.toLiveNotes` 와 같은 순서).
 * [s] GOOD | SHARP | FLAT | WRONG | MISSED | SKIPPED, [c] 음정 오차(cent), [d] 온셋 편차(초, +늦음). MISSED/SKIPPED 는 c·d 생략.
 */
data class PracticeNoteDto(val i: Int = 0, val s: String = "MISSED", val c: Int? = null, val d: Double? = null)

/** 사용자 음정 곡선. [user] 는 MIDI(소수) 또는 무성 null, [hopMs] 간격으로 [startMs] 부터. */
data class PitchCurveDto(val hopMs: Int = 100, val startMs: Int = 0, val user: List<Double?> = emptyList())

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
    val songId: String = "",
    val songTitle: String? = null,
    val sectionId: Long? = null,
    val recordingId: Long? = null,
    val voiceAnalysisId: Long? = null,
    /** 세션 생성 시 키(V29 `initial_key`). 앱은 늘 원키(0)로 열고 실제 키는 완료 때 보낸다. */
    val initialKey: Int? = null,
    /** 최종 키(완료 때 `finalSelectedKey` 로 갱신) */
    val selectedKey: Int = 0,
    val keyChangeCount: Int = 0,
    val keyChanged: Boolean? = null,
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
    val songId: String = "",
    val songTitle: String? = null,
    val imageUrl: String? = null,
    val initialKey: Int? = null,
    val selectedKey: Int = 0,
    val keyChangeCount: Int = 0,
    val keyChanged: Boolean? = null,
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
    val songId: String = "",
    val songTitle: String? = null,
    val initialKey: Int? = null,
    val selectedKey: Int = 0,
    val keyChangeCount: Int = 0,
    val keyChanged: Boolean? = null,
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
    /** 절충안 확장 — 서버가 아직 돌려주지 않으면 null */
    val dataVersion: String? = null,
    val notes: List<PracticeNoteDto>? = null,
    val curve: PitchCurveDto? = null,
)

/** PracticeResultResponse.RetrySection */
data class RetrySectionDto(val sectionId: Long = 0, val selectedKey: Int = 0, val score: Double? = null)
