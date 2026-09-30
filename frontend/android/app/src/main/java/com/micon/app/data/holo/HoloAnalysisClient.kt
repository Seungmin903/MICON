package com.micon.app.data.holo

import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.DeviceRangeNoteDto
import com.micon.app.data.backend.dto.DeviceRangeResultRequest
import com.micon.app.data.backend.dto.VoiceProfileSaveRequest
import com.micon.app.data.backend.dto.VoiceSegmentDto
import com.micon.app.data.backend.dto.VoiceTimbreMetaRequest
import com.micon.app.data.backend.dto.VoiceJobDto
import com.micon.app.data.backend.dto.VoiceStreamBeginRequest
import com.micon.app.data.backend.dto.VoiceStreamCompleteRequest
import com.micon.app.core.network.Json
import com.micon.app.data.onboarding.VoiceSessionAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
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
    data class StreamUpload(val session: Session, val requestId: String, val pcmSha256: String)

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
        songStartMs: Int, dataVersion: String?, recordedAt: Long,
        requestId: String = UUID.randomUUID().toString()): Long? {
        val meta = timbreMeta(requestId, songId, sampleRate, durationMs,
            songStartMs, dataVersion, recordedAt)
        val body = Json.moshi.adapter(VoiceTimbreMetaRequest::class.java).toJson(meta)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val part = MultipartBody.Part.createFormData("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
        return api.submitTimbre(session.id, part, body, session.access).data().analysisId
    }

    private fun timbreMeta(requestId: String, songId: String, sampleRate: Int, durationMs: Int,
        songStartMs: Int, dataVersion: String?, recordedAt: Long): VoiceTimbreMetaRequest = VoiceTimbreMetaRequest(
            requestId = requestId, songId = songId, guideVersion = "holo-v1",
            recordedAt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(recordedAt).atOffset(ZoneOffset.UTC).toLocalDateTime()),
            durationMs = durationMs, sampleRate = sampleRate, startPositionType = "BEGINNING",
            selectedStartMs = songStartMs, actualStartMs = songStartMs,
            instrumentalVersion = dataVersion ?: "v1", lyricsVersion = dataVersion ?: "v1",
            segments = listOf(VoiceSegmentDto(0, durationMs, songStartMs, songStartMs + durationMs)),
        )

    /** Upload each non-overlapping 2 s PCM chunk while capture keeps writing its single local WAV. */
    suspend fun uploadDuringRecording(session: Session, wav: File, rate: Int,
        requestId: String, finished: () -> Boolean): StreamUpload? {
        val chunkBytes = rate * 4
        var started = false
        var index = 0
        var needStatus = true
        var failures = 0
        while (true) {
            try {
                if (!started) {
                    val begin = api.beginTimbreStream(session.id, requestId, VoiceStreamBeginRequest(rate), session.access)
                    if (begin.code() in setOf(404, 405, 501, 503)) return null
                    begin.data()
                    started = true
                }
                if (needStatus) {
                    index = api.timbreStreamStatus(session.id, requestId, session.access).data().nextIndex
                    needStatus = false
                }
                val available = (wav.length() - 44).coerceAtLeast(0).toInt()
                val offset = index * chunkBytes
                val done = finished()
                val count = when {
                    available - offset >= chunkBytes -> chunkBytes
                    done && available > offset -> (available - offset) and -2
                    else -> 0
                }
                if (count > 0) {
                    val pcm = ByteArray(count)
                    RandomAccessFile(wav, "r").use { input ->
                        input.seek(44L + offset)
                        input.readFully(pcm)
                    }
                    index = api.sendTimbreChunk(session.id, requestId, index,
                        pcm.toRequestBody("application/octet-stream".toMediaType()), session.access).data().nextIndex
                    failures = 0
                    continue
                }
                if (done) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    wav.inputStream().buffered().use { input ->
                        val header = ByteArray(44)
                        var headerRead = 0
                        while (headerRead < header.size) {
                            val n = input.read(header, headerRead, header.size - headerRead)
                            if (n < 0) throw IllegalStateException("녹음 파일이 완성되지 않았어요.")
                            headerRead += n
                        }
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    return StreamUpload(session, requestId,
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                }
                delay(250)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                needStatus = true
                if (finished() && ++failures >= 8) return null
                delay((failures.coerceAtLeast(1) * 500L).coerceAtMost(3_000L))
            }
        }
    }

    suspend fun completeTimbreStream(upload: StreamUpload, songId: String, sampleRate: Int,
        durationMs: Int, songStartMs: Int, dataVersion: String?, recordedAt: Long): Long? {
        val meta = timbreMeta(upload.requestId, songId, sampleRate, durationMs,
            songStartMs, dataVersion, recordedAt)
        val request = VoiceStreamCompleteRequest(upload.pcmSha256, meta)
        return api.completeTimbreStream(upload.session.id, upload.requestId, request,
            upload.session.access).data().analysisId
    }

    suspend fun timbreJob(session: Session): VoiceJobDto =
        api.timbreStatus(session.id, session.access).data()

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
