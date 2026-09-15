package com.micon.app.data.onboarding

import com.micon.app.core.network.Json
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.*
import com.micon.app.range.RangeProtocol
import com.micon.app.range.RangeReport
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.File

data class TimbreSubmissionLink(val sessionId: Long, val requestId: String, val terminalFailure: Boolean = false)

class BackendVoiceOnboardingGateway(private val api: BackendApi,
    private val stateStore: SubmissionStateStore = AtomicSubmissionStateStore) : VoiceOnboardingGateway {
    override val available: Boolean get() = true
    private val sessions = ConcurrentHashMap<String, Long>()

    override suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent?,
        onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress {
        if (request.ownerId == null) throw VoiceOnboardingNotReady("로그인하면 음색 분석 결과를 확인할 수 있어요.")
        val access = VoiceSessionAccess(request.ownerId)
        val recording = request.recording
        val linkFile = File(recording.wav.parentFile, recording.wav.name + ".submission.json")
        val linkAdapter = Json.moshi.adapter(TimbreSubmissionLink::class.java)
        val previous = stateStore.read(linkFile)?.let { linkAdapter.fromJson(it) }
        var sessionId = request.range.backendSessionId ?: previous?.sessionId ?: sessions[request.draftId]
        if (sessionId == null) {
            sessionId = api.createVoiceSession(access).data().sessionId
            sessions[request.draftId] = sessionId
        }
        sessions[request.draftId] = sessionId
        if (retry != null) {
            val current = api.timbreStatus(sessionId, access).data()
            val analysisId = current.analysisId ?: throw IllegalStateException("재시도할 분석을 찾을 수 없습니다.")
            val component = when (retry) {
                AnalysisComponent.ECAPA -> "ECAPA"
                AnalysisComponent.SINGER_BYOL -> "SINGER_BYOL"
                AnalysisComponent.ARTIST_SEARCH -> "ARTIST_SEARCH"
            }
            api.retryTimbreComponent(sessionId, analysisId, component, access).data()
            return awaitResult(sessionId, analysisId, access, linkFile, null, onProgress)
        }
        if (request.range.backendSessionId == null) {
            api.saveDeviceRange(sessionId, request.range.toRequest(), access).data()
        }
        val link = if (previous != null && !previous.terminalFailure && previous.sessionId == sessionId) previous
            else TimbreSubmissionLink(sessionId, UUID.randomUUID().toString())
        stateStore.write(linkFile, linkAdapter.toJson(link))
        val meta = recording.toMeta(link.requestId)
        val body = Json.moshi.adapter(VoiceTimbreMetaRequest::class.java).toJson(meta)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val file = MultipartBody.Part.createFormData("file", recording.wav.name,
            recording.wav.asRequestBody("audio/wav".toMediaType()))
        val accepted = api.submitTimbre(sessionId, file, body, access).data()
        return awaitResult(sessionId, accepted.analysisId, access, linkFile, link, onProgress)
    }

    private suspend fun awaitResult(sessionId: Long, analysisId: Long?, access: VoiceSessionAccess,
        linkFile: File, link: TimbreSubmissionLink?, onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress {
        repeat(300) {
            val result = try { api.voiceOnboardingResult(sessionId, access).data().toProgress(analysisId) }
            catch (_: Exception) { delay(2_000); return@repeat }
            onProgress(result)
            if (!result.analyzing && result.sessionStatus !in setOf(VoiceSessionStatus.CREATED,
                    VoiceSessionStatus.RANGE_IN_PROGRESS, VoiceSessionStatus.UPLOADING)) {
                if (!result.canSave && link != null) stateStore.write(linkFile,
                    Json.moshi.adapter(TimbreSubmissionLink::class.java).toJson(link.copy(terminalFailure = true)))
                return result
            }
            delay(2_000)
        }
        throw IllegalStateException("음색 분석 대기 시간이 초과됐어요.")
    }

    override suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt {
        val owner = request.ownerId ?: throw IllegalStateException("로그인이 필요합니다.")
        val sessionId = sessions[request.draftId] ?: result.sessionId.toLongOrNull()
            ?: throw IllegalStateException("분석 세션을 찾을 수 없습니다.")
        val saved = api.saveVoiceProfile(sessionId,
            VoiceProfileSaveRequest(result.version), VoiceSessionAccess(owner)).data()
        return ProfileSaveReceipt(saved.sessionId.toString(), result.version, saved.profileId.toString())
    }

    override suspend fun recover(ownerId: Long): VoiceAnalysisProgress? {
        val access = VoiceSessionAccess(ownerId)
        val response = api.activeVoiceSession(access)
        if (response.code() == 404) return null
        val active = response.data()
        val job = runCatching { api.timbreStatus(active.sessionId, access).data() }.getOrNull()
        return api.voiceOnboardingResult(active.sessionId, access).data().toProgress(job?.analysisId)
    }

    override suspend fun latestSaved(ownerId: Long): SavedVoiceOnboardingProfile? {
        val profileResponse = api.latestVoiceProfile()
        if (profileResponse.code() == 404) return null
        val profile = profileResponse.data()
        val result = api.voiceOnboardingResult(profile.sessionId, VoiceSessionAccess(ownerId)).data()
        val range = result.range?.toReport(profile.sessionId) ?: return null
        val progress = result.toProgress()
        return SavedVoiceOnboardingProfile(
            ProfileSaveReceipt(profile.sessionId.toString(), result.resultVersion, profile.profileId.toString()),
            range,
            progress,
        )
    }

    override suspend fun cancel(ownerId: Long, result: VoiceAnalysisProgress) {
        val analysisId = result.analysisId ?: throw IllegalStateException("취소할 분석 작업을 찾을 수 없습니다.")
        api.cancelTimbre(result.sessionId.toLong(), analysisId, VoiceSessionAccess(ownerId)).data()
    }

    private fun com.micon.app.range.RangeReport.toRequest() = DeviceRangeResultRequest(
        measurementId = analysisId ?: UUID.randomUUID().toString(),
        measuredAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(java.time.LocalDateTime.now(ZoneOffset.UTC)),
        protocolVersion = protocolVersion, lowMidi = lowMidi?.toDouble(), highMidi = highMidi?.toDouble(),
        noteResults = notes.map { DeviceRangeNoteDto(it.midi, it.status) },
        measurementStatus = if (availability == "available") "AVAILABLE" else "INSUFFICIENT_DATA",
        qualityStatus = if (availability == "available") "VALID" else "INSUFFICIENT_VOICE",
    )

    private fun com.micon.app.data.singanalysis.SavedRecording.toMeta(requestId: String): VoiceTimbreMetaRequest {
        fun ms(value: Double) = (value * 1000).toInt().coerceAtLeast(0)
        val position = when (meta.scope) { "VERSE_2" -> "VERSE_2"; "HIGHLIGHT" -> "HIGHLIGHT"; else -> "BEGINNING" }
        return VoiceTimbreMetaRequest(requestId, songId = meta.songId,
            guideVersion = "v4", recordedAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                Instant.ofEpochMilli(meta.recordedAt).atOffset(ZoneOffset.UTC).toLocalDateTime()),
            durationMs = ms(meta.durationSec), sampleRate = meta.sampleRate,
            startPositionType = position, selectedStartMs = ms(meta.plannedStartSec), actualStartMs = ms(meta.sungStartSec),
            instrumentalVersion = meta.dataVersion ?: "v1", lyricsVersion = meta.dataVersion ?: "v1",
            segments = meta.takes.map { VoiceSegmentDto(ms(it.wavStartSec), ms(it.wavEndSec), ms(it.songStartSec), ms(it.songEndSec)) })
    }

    private fun VoiceOnboardingResultDto.toProgress(analysisId: Long? = null): VoiceAnalysisProgress {
        fun status(name: String) = components.firstOrNull { it.component == name }?.status.toComponentStatus()
        return VoiceAnalysisProgress(sessionId.toString(), resultVersion,
            ecapa = status("ECAPA"), byol = status("SINGER_BYOL"),
            artistSearch = if (artists.isNotEmpty()) ComponentStatus.COMPLETED else ComponentStatus.PENDING,
            artists = artists.map { SimilarVoiceArtist(it.artistId, it.name, it.imageUrl, it.similarity) },
            sessionStatus = runCatching { VoiceSessionStatus.valueOf(this.status) }.getOrDefault(VoiceSessionStatus.FAILED),
            saveAllowed = saveAllowed, rangeStatus = rangeStatus,
            range = range?.toReport(sessionId),
            recordingId = null, savedProfileExists = savedProfileExists, analysisId = analysisId,
            expiresAtMillis = runCatching { java.time.LocalDateTime.parse(expiresAt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull())
    }

    private fun VoiceRangeDto.toReport(sessionId: Long) = RangeReport(
        protocolVersion = RangeProtocol.VERSION,
        notes = noteResults.orEmpty(),
        lowMidi = lowMidi?.toInt(),
        highMidi = highMidi?.toInt(),
        stableIntervals = emptyList(),
        stopReason = if (measurementStatus == "AVAILABLE") "completed" else "insufficient_data",
        lowerBoundaryStatus = if (lowMidi != null) "FOUND" else "NOT_FOUND",
        upperBoundaryStatus = if (highMidi != null) "FOUND" else "NOT_FOUND",
        availability = if (measurementStatus == "AVAILABLE") "available" else "insufficient_data",
        verification = "server_verified",
        backendSessionId = sessionId,
    )

    private fun String?.toComponentStatus() = runCatching { ComponentStatus.valueOf(this ?: "PENDING") }
        .getOrDefault(ComponentStatus.PENDING)

    private fun <T> Response<ApiResponse<T>>.data(): T {
        if (!isSuccessful) throw IllegalStateException(body()?.message ?: "서버 요청에 실패했어요 (${code()}).")
        return body()?.data ?: throw IllegalStateException("서버 응답이 비어 있어요.")
    }
}
