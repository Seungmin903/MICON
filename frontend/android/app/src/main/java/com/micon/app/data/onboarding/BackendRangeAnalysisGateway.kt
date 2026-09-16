package com.micon.app.data.onboarding

import com.micon.app.core.network.Json
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.range.*
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

data class RangeServerLink(val measurementId: String, val sessionId: Long, val requestId: String,
    val measuredAt: String, val analysisId: Long? = null)

/** BE is the only network destination. A lost acceptance response reuses the same WAV and request ID. */
class BackendRangeAnalysisGateway(private val api: BackendApi, private val ownerId: Long,
    private val stateFile: File,
    private val stateStore: SubmissionStateStore = AtomicSubmissionStateStore) : RangeAnalysisGateway {
    private val adapter = Json.moshi.adapter(RangeServerLink::class.java)
    private val submissionAdapter = Json.moshi.adapter(RangeSubmission::class.java)
    private val mapAdapter = Json.moshi.adapter(Map::class.java)

    override suspend fun analyze(wav: File, submission: RangeSubmission): RangeReport {
        val access = VoiceSessionAccess(ownerId)
        var link = stateStore.read(stateFile)?.let { adapter.fromJson(it) }
            ?.takeIf { it.measurementId == submission.sessionId }
        if (link == null) {
            link = RangeServerLink(submission.sessionId, api.createVoiceSession(access).data().sessionId,
                UUID.randomUUID().toString(), LocalDateTime.now(ZoneOffset.UTC).toString())
            persist(link)
        }
        @Suppress("UNCHECKED_CAST")
        val meta = (mapAdapter.fromJson(submissionAdapter.toJson(submission)) as Map<String, Any?>).toMutableMap()
        meta.remove("sessionId")
        meta.remove("measurementType")
        meta.remove("keepOriginal")
        meta.remove("minMidi")
        meta.remove("maxMidi")
        meta["requestId"] = link.requestId
        meta["measurementId"] = submission.sessionId
        meta["measuredAt"] = link.measuredAt
        meta["attemptNo"] = 1
        meta["contractVersion"] = "v1"
        meta["parentAnalysisId"] = submission.parentAnalysisId?.toLongOrNull()
        val accepted = api.submitRange(link.sessionId,
            MultipartBody.Part.createFormData("file", wav.name, wav.asRequestBody("audio/wav".toMediaType())),
            mapAdapter.toJson(meta).toRequestBody("application/json".toMediaType()), access).data()
        link = link.copy(analysisId = accepted.analysisId ?: link.analysisId)
        persist(link)
        repeat(300) {
            val result = api.voiceOnboardingResult(link.sessionId, access).data()
            val range = result.range
            if (range != null && result.rangeStatus == "AVAILABLE") {
                val notes = checkNotNull(range.noteResults) { "서버 음역 결과에 음별 측정 값이 없습니다." }
                return RangeReport(submission.protocolVersion, notes, range.lowMidi?.toInt(), range.highMidi?.toInt(),
                    emptyList(), submission.stopReason, submission.lowerBoundaryStatus, submission.upperBoundaryStatus,
                    "available", analysisId = checkNotNull(link.analysisId).toString(), verification = "model_verified",
                    backendSessionId = link.sessionId, sampling = submission.sampling)
            }
            if (result.rangeStatus in setOf("FAILED", "INSUFFICIENT_DATA") ||
                result.components.any { it.component == "RANGE" && it.status in setOf("FAILED", "INSUFFICIENT_DATA", "CANCELED") }) {
                stateStore.delete(stateFile)
                error(result.components.firstOrNull { it.component == "RANGE" }?.failureReason
                    ?: "음역 분석을 완료하지 못했습니다. 녹음은 기기에 남아 있습니다.")
            }
            delay(2_000)
        }
        error("음역 분석 대기 시간이 초과됐습니다. 녹음은 기기에 남아 있습니다.")
    }

    private fun persist(value: RangeServerLink) {
        stateStore.write(stateFile, adapter.toJson(value))
    }

    private fun <T> Response<ApiResponse<T>>.data(): T {
        check(isSuccessful) { "음역 서버 요청 실패 (${code()}). 녹음은 기기에 남아 있습니다." }
        return checkNotNull(body()?.data) { "음역 서버 응답이 비어 있습니다." }
    }
}
