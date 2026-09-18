package com.micon.app.data.practice

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.BackendRequestException
import com.micon.app.data.backend.dto.PracticeCompleteRequest
import com.micon.app.data.backend.dto.PracticeCreateRequest
import com.micon.app.data.backend.dto.PitchCurveDto
import com.micon.app.data.backend.dto.PracticeDetailDto
import com.micon.app.data.backend.dto.PracticeNoteDto
import com.micon.app.data.backend.dto.PracticeReportDto
import com.micon.app.data.backend.dto.PracticeResultSaveRequest
import com.micon.app.data.backend.dto.PracticeSummaryDto
import com.micon.app.data.backend.dto.RetrySectionDto
import com.micon.app.data.backend.dto.SectionEvaluationDto
import com.micon.app.data.backend.unwrapData

/** 백엔드 `PracticeRequest.ActiveStatus` — `PATCH /practices/{id}/status` 로 바꿀 수 있는 진행 상태. */

/** 연습 세션 한 건(`PracticeResponse.Detail`). [status] 는 READY·RECORDING·IN_PROGRESS·PAUSED·COMPLETED·ABORTED·FAILED. */
data class PracticeRecord(
    val id: Long,
    val songId: String,
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
    val songId: String,
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

/**
 * 노트 하나의 라이브 판정(절충안 확장). [index] 는 practice-assets `targetNotes` 를 sourceStart 순으로 정렬한 위치
 * (`NoteNormalizer.toLiveNotes` 순서 = `LiveNote.index`). [status] GOOD·SHARP·FLAT·WRONG·MISSED·SKIPPED.
 * [cents] 음정 오차(+높음), [deviation] 온셋 편차 초(+늦음). 판정 없는 값은 null.
 */
data class JudgedNote(val index: Int, val status: String, val cents: Int? = null, val deviation: Double? = null)

/** 사용자 음정 곡선(절충안 확장). [user] 는 [hopMs] 간격의 MIDI(소수) 또는 무성 null, 첫 값의 곡 시각은 [startMs]. */
data class PitchCurve(val hopMs: Int, val startMs: Int, val user: List<Double?>) {
    val isEmpty: Boolean get() = user.none { it != null }
}

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
    /** practice-assets 의 dataVersion (notes 인덱스 기준) */
    val dataVersion: String? = null,
    /** 판정된 음표만. 서버 한도 [RemotePracticeRepository.MAX_NOTES] */
    val notes: List<JudgedNote> = emptyList(),
    val curve: PitchCurve? = null,
)

data class RetrySection(val sectionId: Long, val selectedKey: Int, val score: Double?)

/** 저장된 연습 결과(`PracticeResultResponse.Report`). */
data class PracticeReport(
    val practiceId: Long,
    val songId: String,
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
    /** 절충안 확장. 서버가 아직 저장·반환하지 않으면 null/빈 값 — 화면은 이 경우 음절·곡선 카드를 숨긴다. */
    val dataVersion: String? = null,
    val notes: List<JudgedNote> = emptyList(),
    val curve: PitchCurve? = null,
) {
    val hasDetail: Boolean get() = notes.isNotEmpty() || (curve != null && !curve.isEmpty)

    /** 결과 화면이 쓰는 세션 정보. 세션 상세 API 가 없어져 리포트 응답으로 대신 만든다(시각은 analyzedAt 로 대체). */
    fun toRecord(): PracticeRecord = PracticeRecord(
        id = practiceId, songId = songId, songTitle = songTitle, sectionId = sectionId, recordingId = null, voiceAnalysisId = null,
        selectedKey = selectedKey, playbackSpeed = null, practiceScope = practiceScope, status = status ?: "COMPLETED",
        startedAt = analyzedAt, endedAt = analyzedAt, durationMs = durationMs, createdAt = analyzedAt,
    )
}

/**
 * 연습 기록. 백엔드 `PracticeController`(`/practices`)만 본다 — 로컬 엔진 job 은 더 쓰지 않는다.
 * `GET …/analysis`·`POST …/analysis/retry` 는 쓰지 않는다: 서버 analysis 는 앱이 저장한 결과 행의 상태를 돌려주고, retry 는 그 점수를 지운다.
 *
 * 세션 흐름(서버 규칙): [create](POST /practices/session) → READY → [complete]. 상태변경(PATCH status)·세션 상세(GET /practices/{practiceId})는
 * BE 2026-09-16 명세 정리로 제거됐다 — 세션 정보는 리포트 상세(GET /practices/report/{practiceId})에서 읽는다. [cancel] 은 READY~PAUSED → ABORTED.
 * [savePerformance] 는 COMPLETED 세션에만 된다. 모두 로그인 필요(비회원 401).
 */
interface PracticeRepository {
    suspend fun create(songId: String, selectedKey: Int, practiceScope: String = "FULL_SONG"): PracticeRecord
    suspend fun complete(practiceId: Long, durationMs: Int): PracticeRecord
    suspend fun cancel(practiceId: Long): PracticeRecord
    suspend fun savePerformance(practiceId: Long, result: PracticeResultInput): PracticeReport
    /** 최신순 목록. size 최대 100. [date](yyyy-MM-dd) 를 주면 그 날짜만. */
    suspend fun history(page: Int = 0, size: Int = 50, date: String? = null): PracticePage
    /** 저장된 결과. 없으면(404 PRACTICE_RESULT_NOT_FOUND) null. */
    suspend fun report(practiceId: Long): PracticeReport?
    /** 연습 기록(세션+결과) 삭제. 녹음·음성 분석 데이터는 남는다(BE 정책). 없거나 타인 것이면 404. */
    suspend fun delete(practiceId: Long)
}

class RemotePracticeRepository(private val api: BackendApi) : PracticeRepository {
    override suspend fun create(songId: String, selectedKey: Int, practiceScope: String): PracticeRecord =
        api.createPractice(PracticeCreateRequest(songId = songId, selectedKey = selectedKey, practiceScope = practiceScope)).unwrapData().toModel()

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
                dataVersion = result.dataVersion,
                notes = result.notes.take(MAX_NOTES).map { PracticeNoteDto(it.index, it.status, it.cents, it.deviation?.let(::sec3)) }.ifEmpty { null },
                curve = result.curve?.takeIf { !it.isEmpty }?.let { PitchCurveDto(it.hopMs, it.startMs, it.user.take(MAX_CURVE_POINTS).map { m -> m?.let(::midi2) }) },
            ),
        ).unwrapData().toModel()

    override suspend fun history(page: Int, size: Int, date: String?): PracticePage {
        val dto = api.practices(page, size.coerceIn(1, 100), date).unwrapData()
        return PracticePage(dto.content.map { it.toModel() }, dto.page, dto.size, dto.totalElements, dto.totalPages)
    }

    override suspend fun report(practiceId: Long): PracticeReport? = try {
        api.practiceReport(practiceId).unwrapData().toModel()
    } catch (e: BackendRequestException) {
        if (e.status == 404 && e.code == "PRACTICE_RESULT_NOT_FOUND") null else throw e
    }

    override suspend fun delete(practiceId: Long) {
        api.deletePractice(practiceId).unwrapData()
    }

    companion object {
        /** 서버 검증값(PracticeResultRequest): summaryText ≤ 500, sections ≤ 20, section.summary ≤ 120 */
        const val SUMMARY_MAX = 500
        const val MAX_SECTIONS = 20
        const val SECTION_SUMMARY_MAX = 120
        /** 절충안 확장 제안값(BE 요청 문서 1번): notes ≤ 600, curve.user ≤ 4,000(100ms × 6분 40초) */
        const val MAX_NOTES = 600
        const val MAX_CURVE_POINTS = 4000

        /** 온셋 편차·MIDI 는 소수 셋째·둘째 자리까지만 보낸다(JSON 크기). */
        fun sec3(value: Double): Double = Math.round(value * 1000) / 1000.0
        fun midi2(value: Double): Double = Math.round(value * 100) / 100.0

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
            dataVersion = dataVersion,
            notes = notes.orEmpty().map { JudgedNote(it.i, it.s, it.c, it.d) },
            curve = curve?.let { PitchCurve(it.hopMs.coerceAtLeast(1), it.startMs, it.user) },
        )
    }
}
