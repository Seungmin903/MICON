package com.micon.app.data.holo

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.DeviceRangeNoteDto
import com.micon.app.data.backend.dto.DeviceRangeResultRequest
import com.micon.app.data.backend.dto.VoiceProfileSaveRequest
import com.micon.app.data.backend.dto.VoiceSegmentDto
import com.micon.app.data.backend.dto.VoiceTimbreMetaRequest
import com.micon.app.core.network.Json
import com.micon.app.data.onboarding.VoiceSessionAccess
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 홀로 화면 → 서버. 회원·게스트 공통(`X-Guest-Session-Token`).
 *
 * 1. 세션 생성 → 2. 기기 측정 음역 저장(range-result) → 3. 곡을 골랐으면 음색 분석 업로드 → 4. 결과 폴링(닮은 가수)
 * 서버가 없어도 1~2 실패는 결과 화면을 막지 않는다 — 음역·안정성은 기기 계산이다.
 */
class HoloAnalysisClient(private val api: BackendApi) {
    class Session(val id: Long, val access: VoiceSessionAccess)

    suspend fun createSession(ownerId: Long?): Session {
        val created = api.createVoiceSession(VoiceSessionAccess(ownerId)).data()
        return Session(created.sessionId, VoiceSessionAccess(ownerId, created.guestSessionToken))
    }

    suspend fun submitRange(session: Session, result: HoloResult) {
        api.saveDeviceRange(session.id, DeviceRangeResultRequest(
            measurementId = "holo-${result.measuredAt}",
            measuredAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now(ZoneOffset.UTC)),
            protocolVersion = "holo-v1",
            lowMidi = result.lowMidi.toDouble(), highMidi = result.highMidi.toDouble(),
            noteResults = result.notes.map { DeviceRangeNoteDto(it.midi, it.status, stability = it.stability, holdMs = it.holdMs) },
            voiceQuality = result.voiceQuality,
            measurementStatus = if (result.usable) "AVAILABLE" else "INSUFFICIENT_DATA",
            qualityStatus = if (result.usable) "VALID" else "INSUFFICIENT_VOICE",
        ), session.access).data()
    }

    /** 곡을 부른 녹음(PCM16 모노 WAV)을 음색 분석으로 올린다. 반환값은 분석 id */
    suspend fun submitTimbre(session: Session, wav: File, songId: String, sampleRate: Int, durationMs: Int,
        songStartMs: Int, dataVersion: String?, recordedAt: Long): Long? {
        val meta = VoiceTimbreMetaRequest(
            requestId = UUID.randomUUID().toString(), songId = songId, guideVersion = "holo-v1",
            recordedAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(recordedAt).atOffset(ZoneOffset.UTC).toLocalDateTime()),
            durationMs = durationMs, sampleRate = sampleRate, startPositionType = "BEGINNING",
            selectedStartMs = songStartMs, actualStartMs = songStartMs,
            instrumentalVersion = dataVersion ?: "v1", lyricsVersion = dataVersion ?: "v1",
            segments = listOf(VoiceSegmentDto(0, durationMs, songStartMs, songStartMs + durationMs)),
        )
        val body = Json.moshi.adapter(VoiceTimbreMetaRequest::class.java).toJson(meta)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val part = MultipartBody.Part.createFormData("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
        return api.submitTimbre(session.id, part, body, session.access).data().analysisId
    }

    class Poll(val artists: List<HoloArtist>, val saveAllowed: Boolean, val version: Long, val done: Boolean)

    /** 닮은 가수가 나오거나 분석이 끝날 때까지 2초 간격으로 확인 */
    suspend fun poll(session: Session, maxSeconds: Int = 120, onUpdate: (Poll) -> Unit) {
        repeat(maxSeconds / 2) {
            val r = runCatching { api.voiceOnboardingResult(session.id, session.access).data() }.getOrNull()
            if (r != null) {
                val artists = r.artists.map { HoloArtist(it.artistId, it.name, it.imageUrl, it.similarity) }
                    .filter { it.artistId > 0 && it.name.isNotBlank() }.sortedByDescending { it.similarity }.take(3)
                val finished = r.status in setOf("READY_TO_SAVE", "SAVED", "FAILED", "INSUFFICIENT_DATA", "CANCELED", "EXPIRED") &&
                    r.artistSearchStatus !in setOf("PENDING", "QUEUED", "RUNNING")
                onUpdate(Poll(artists, r.saveAllowed, r.resultVersion, finished))
                if (finished) return
            }
            delay(2_000)
        }
    }

    suspend fun saveProfile(session: Session, version: Long): Long =
        api.saveVoiceProfile(session.id, VoiceProfileSaveRequest(version), session.access).data().profileId

    private fun <T> Response<ApiResponse<T>>.data(): T {
        if (!isSuccessful) throw IllegalStateException(body()?.message ?: "서버 요청에 실패했어요 (${code()}).")
        return body()?.data ?: throw IllegalStateException("서버 응답이 비어 있어요.")
    }
}
