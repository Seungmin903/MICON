package com.micon.app.data.practice

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.BackendRequestException
import com.micon.app.data.backend.dto.PracticeCompleteRequest
import com.micon.app.data.backend.dto.PracticeCreateRequest
import com.micon.app.data.backend.dto.PracticeDetailDto
import com.micon.app.data.backend.dto.PracticeReportDto
import com.micon.app.data.backend.dto.PracticeResultSaveRequest
import com.micon.app.data.backend.dto.PracticeStatusRequest
import com.micon.app.data.backend.dto.PracticeSummaryDto
import com.micon.app.data.backend.dto.RetrySectionDto
import com.micon.app.data.backend.dto.SectionEvaluationDto
import com.micon.app.data.backend.unwrapData

/** 백엔드 `PracticeRequest.ActiveStatus` — `PATCH /practices/{id}/status` 로 바꿀 수 있는 진행 상태. */
enum class PracticeActiveStatus { RECORDING, IN_PROGRESS, PAUSED }

/** 연습 세션 한 건(`PracticeResponse.Detail`). [status] 는 READY·RECORDING·IN_PROGRESS·PAUSED·COMPLETED·ABORTED·FAILED. */
data class PracticeRecord(
    val id: Long,
    val songId: Long,
    val songTitle: String?,
    val sectionId: Long?,
    val recordingId: Long?,
    val voiceAnalysisId: Long?,
    val selectedKey: Int,
    val playbackSpeed: Double?,
    val practiceScope: String?,
    val status: String,
    val startedAt: String?,
    val endedAt: String?,
    val durationMs: Int?,
    val createdAt: String?,
) {
    val isCompleted: Boolean get() = status == "COMPLETED"
}

/** 평가 탭 목록 항목(`PracticeHistoryResponse.Summary`). */
data class PracticeSummary(
    val id: Long,
    val songId: Long,
    val songTitle: String?,
    val imageUrl: String?,
    val selectedKey: Int,
    val practiceScope: String?,
    val sectionId: Long?,
    val status: String,
    val startedAt: String?,
    val durationMs: Int?,
    val resultStatus: String?,
    val overallScore: Double?,
)

data class PracticePage(val items: List<PracticeSummary>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Int)

data class SectionEvaluation(val sectionId: Long, val score: Double?, val measured: Boolean, val summary: String?)

/** `PUT /practices/{id}/performance` 입력. 앱 라이브 채점(LiveScorer) 결과를 담는다 — [PracticeScoreMapper] 가 만든다. */
data class PracticeResultInput(
    /** COMPLETED | INSUFFICIENT_DATA | FAILED */
    val status: String = "COMPLETED",
    val overallScore: Double?,
    val pitchScore: Double?,
    val rhythmScore: Double?,
    val sustainScore: Double? = null,
    val summaryText: String?,
    val sections: List<SectionEvaluation> = emptyList(),
)

data class RetrySection(val sectionId: Long, val selectedKey: Int, val score: Double?)

/** 저장된 연습 결과(`PracticeResultResponse.Report`). */
data class PracticeReport(
    val practiceId: Long,
    val songId: Long,
    val songTitle: String?,
    val selectedKey: Int,
    val practiceScope: String?,
    val sectionId: Long?,
    val durationMs: Int?,
    val status: String?,
    val overallScore: Double?,
    val pitchScore: Double?,
    val sustainScore: Double?,
    val rhythmScore: Double?,
    val summaryText: String?,
    val sections: List<SectionEvaluation>,
    val weakSection: RetrySection?,
    val analyzedAt: String?,
)

/**
 * 연습 기록. 백엔드 `PracticeController`(`/practices`)만 본다 — 로컬 엔진 job 은 더 쓰지 않는다.
 * `GET …/analysis`·`POST …/analysis/retry` 는 쓰지 않는다: 서버 analysis 는 앱이 저장한 결과 행의 상태를 돌려주고, retry 는 그 점수를 지운다.
 *
 * 세션 흐름(서버 규칙): [create] → READY. [complete] 는 RECORDING·IN_PROGRESS·PAUSED 에서만 되므로 시작 때 [updateStatus] 로
 * IN_PROGRESS 를 한 번 보낸다(같은 상태로 다시 보내면 409 INVALID_PRACTICE_STATE). [cancel] 은 READY~PAUSED → ABORTED.
 * [savePerformance] 는 COMPLETED 세션에만 된다. 모두 로그인 필요(비회원 401).
 */
interface PracticeRepository {
    suspend fun create(songId: Long, selectedKey: Int, practiceScope: String = "FULL_SONG"): PracticeRecord
    suspend fun updateStatus(practiceId: Long, status: PracticeActiveStatus): PracticeRecord
    suspend fun complete(practiceId: Long, durationMs: Int): PracticeRecord
    suspend fun cancel(practiceId: Long): PracticeRecord
    suspend fun savePerformance(practiceId: Long, result: PracticeResultInput): PracticeReport
    suspend fun get(practiceId: Long): PracticeRecord
    /** 최신순 목록. size 최대 100. */
    suspend fun history(page: Int = 0, size: Int = 50): PracticePage
    /** 저장된 결과. 없으면(404 PRACTICE_RESULT_NOT_FOUND) null. */
    suspend fun report(practiceId: Long): PracticeReport?
}

class RemotePracticeRepository(private val api: BackendApi) : PracticeRepository {
    override suspend fun create(songId: Long, selectedKey: Int, practiceScope: String): PracticeRecord =
        api.createPractice(PracticeCreateRequest(songId = songId, selectedKey = selectedKey, practiceScope = practiceScope)).unwrapData().toModel()

    override suspend fun updateStatus(practiceId: Long, status: PracticeActiveStatus): PracticeRecord =
        api.updatePracticeStatus(practiceId, PracticeStatusRequest(status.name)).unwrapData().toModel()

    override suspend fun complete(practiceId: Long, durationMs: Int): PracticeRecord =
        api.completePractice(practiceId, PracticeCompleteRequest(durationMs.coerceAtLeast(0))).unwrapData().toModel()

    override suspend fun cancel(practiceId: Long): PracticeRecord = api.cancelPractice(practiceId).unwrapData().toModel()

    override suspend fun savePerformance(practiceId: Long, result: PracticeResultInput): PracticeReport =
        api.savePracticePerformance(
            practiceId,
            PracticeResultSaveRequest(
                status = result.status,
                overallScore = result.overallScore?.let(::score2),
                pitchScore = result.pitchScore?.let(::score2),
                sustainScore = result.sustainScore?.let(::score2),
                rhythmScore = result.rhythmScore?.let(::score2),
                summaryText = result.summaryText?.take(SUMMARY_MAX),
                sections = result.sections.take(MAX_SECTIONS).map {
                    SectionEvaluationDto(it.sectionId, it.score?.let(::score2), it.measured, it.summary?.take(SECTION_SUMMARY_MAX))
                },
            ),
        ).unwrapData().toModel()

    override suspend fun get(practiceId: Long): PracticeRecord = api.practice(practiceId).unwrapData().toModel()

    override suspend fun history(page: Int, size: Int): PracticePage {
        val dto = api.practices(page, size.coerceIn(1, 100)).unwrapData()
        return PracticePage(dto.content.map { it.toModel() }, dto.page, dto.size, dto.totalElements, dto.totalPages)
    }

    override suspend fun report(practiceId: Long): PracticeReport? = try {
        api.practiceReport(practiceId).unwrapData().toModel()
    } catch (e: BackendRequestException) {
        if (e.status == 404 && e.code == "PRACTICE_RESULT_NOT_FOUND") null else throw e
    }

    companion object {
        /** 서버 검증값(PracticeResultRequest): summaryText ≤ 500, sections ≤ 20, section.summary ≤ 120 */
        const val SUMMARY_MAX = 500
        const val MAX_SECTIONS = 20
        const val SECTION_SUMMARY_MAX = 120

        /** 서버 BigDecimal(0.00~100.00)에 맞춰 소수 둘째 자리로 반올림·클램프 */
        fun score2(value: Double): Double = (Math.round(value.coerceIn(0.0, 100.0) * 100) / 100.0)

        private fun PracticeDetailDto.toModel() = PracticeRecord(
            practiceId, songId, songTitle, sectionId, recordingId, voiceAnalysisId, selectedKey, playbackSpeed, practiceScope,
            status ?: "UNKNOWN", startedAt, endedAt, durationMs, createdAt,
        )

        private fun PracticeSummaryDto.toModel() = PracticeSummary(
            practiceId, songId, songTitle, imageUrl, selectedKey, practiceScope, sectionId, status ?: "UNKNOWN", startedAt, durationMs, resultStatus, overallScore,
        )

        private fun SectionEvaluationDto.toModel() = SectionEvaluation(sectionId, score, measured, summary)
        private fun RetrySectionDto.toModel() = RetrySection(sectionId, selectedKey, score)

        private fun PracticeReportDto.toModel() = PracticeReport(
            practiceId, songId, songTitle, selectedKey, practiceScope, sectionId, durationMs, status,
            overallScore, pitchScore, sustainScore, rhythmScore, summaryText, sections.map { it.toModel() }, weakSection?.toModel(), analyzedAt,
        )
    }
}
