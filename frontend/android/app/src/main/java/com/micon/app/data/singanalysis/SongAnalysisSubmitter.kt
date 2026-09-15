package com.micon.app.data.singanalysis

import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * 불러서 분석하기 녹음을 서버 분석에 넘긴다. 화면(`SingAnalysisViewModel`)은 이 인터페이스만 본다.
 *
 * 운영 흐름(backend `RecordingController`·`VoiceAnalysisController`, 앱은 백엔드만 본다):
 * 1. `POST /recordings` — multipart `file`(WAV), `attemptNo` → recordingId
 * 2. `POST /voice-analyses` — `{ recordingId, method: "SONG", songId }` → analysisId
 * 3. `GET /voice-analyses/{id}` 폴링 → COMPLETED 면 `GET /voice-analyses/{id}/report` → 리포트 탭
 *
 * 아직 서버 계약에 없는 것: 메타의 [SingRecordingMeta.takes](카운트인·일시정지 구간, WAV↔곡 시각)·선택 키·부른 범위.
 * BE 가 요청 필드를 열면 2단계 요청에 같이 보낸다.
 */
interface SongAnalysisSubmitter {
    /**
     * @param onProgress 진행 문구("녹음을 올리고 있어요 42%" 등). 화면이 그대로 보여 준다.
     */
    suspend fun submit(recording: SavedRecording, onProgress: (String) -> Unit = {}): SubmitResult
}

sealed interface SubmitResult {
    /** 접수됨. [analysisId] 로 상태를 폴링한다(서버가 id 를 안 줬으면 null). */
    data class Accepted(val analysisId: Long?, val message: String = "분석을 요청했어요. 결과는 리포트에서 확인할 수 있어요.") : SubmitResult
    /** 지금은 서버 분석을 요청할 수 없는 정상 상태(비회원, AI 연동 꺼짐). 오류 아님 — 다시 요청할 수 있다 */
    data class Unavailable(val message: String) : SubmitResult
    /** 시도했지만 실패 */
    data class Failed(val message: String) : SubmitResult
}

/**
 * 백엔드 구현. 업로드가 끝난 녹음은 [SingRecordingMeta.serverRecordingId] 로 기억해 재요청 때 다시 올리지 않는다.
 * 비회원은 서버를 부르지 않는다. 분석 생성이 503(`VOICE_ANALYSIS_DISPATCH_FAILED`, AI 연동 꺼짐)이면 녹음은 서버에 남고 "미준비" 로 알린다.
 */
class BackendSongAnalysisSubmitter(
    private val voice: VoiceAnalysisRepository,
    private val isLoggedIn: () -> Boolean,
    /** 서버 id 가 생기면 녹음 메타 JSON 을 다시 쓴다. IO 디스패처에서 불린다. */
    private val persist: (SingRecordingMeta) -> Unit,
) : SongAnalysisSubmitter {

    override suspend fun submit(recording: SavedRecording, onProgress: (String) -> Unit): SubmitResult {
        if (!isLoggedIn()) return SubmitResult.Unavailable(LOGIN_REQUIRED)
        var meta = recording.meta

        val recordingId = meta.serverRecordingId ?: run {
            val bytes = recording.wav.length()
            if (bytes > MAX_UPLOAD_BYTES) {
                return SubmitResult.Failed(
                    "녹음 파일이 %.1f MB 로 서버 한도(25 MB)를 넘어요. 더 짧은 범위로 다시 불러 주세요.".format(Locale.US, bytes / 1_048_576.0),
                )
            }
            onProgress("녹음을 올리고 있어요 0%")
            val id = try {
                voice.upload(recording.wav, attemptNo = 1) { p -> onProgress("녹음을 올리고 있어요 ${(p * 100).toInt()}%") }
            } catch (e: VoiceApiException) {
                return uploadFailure(e)
            } catch (e: IOException) {
                return SubmitResult.Failed(NETWORK)
            }
            meta = meta.copy(serverRecordingId = id, serverAnalysisId = null)
            save(meta)
            id
        }

        onProgress("분석을 요청하고 있어요")
        val analysis = try {
            voice.create(recordingId, meta.songId, VoiceAnalysisRepository.METHOD_SONG)
        } catch (e: VoiceApiException) {
            return createFailure(e, meta)
        } catch (e: IOException) {
            return SubmitResult.Failed(NETWORK)
        }
        save(meta.copy(serverAnalysisId = analysis.id))
        return SubmitResult.Accepted(analysis.id)
    }

    private suspend fun save(meta: SingRecordingMeta) = withContext(Dispatchers.IO) { runCatching { persist(meta) } }

    private fun uploadFailure(e: VoiceApiException): SubmitResult = when {
        e.status == 401 -> SubmitResult.Unavailable(LOGIN_REQUIRED)
        e.status == 413 || e.code == "INVALID_RECORDING_FILE" ->
            SubmitResult.Failed("서버가 녹음 파일을 받지 않았어요(형식 또는 크기). 다시 불러서 저장해 주세요.")
        e.code == "RECORDING_UPLOAD_FAILED" -> SubmitResult.Failed("서버가 녹음을 저장하지 못했어요. 잠시 후 다시 요청해 주세요.")
        else -> SubmitResult.Failed("업로드 실패: ${e.message}")
    }

    private suspend fun createFailure(e: VoiceApiException, meta: SingRecordingMeta): SubmitResult = when {
        e.status == 401 -> SubmitResult.Unavailable(LOGIN_REQUIRED)
        e.code == "VOICE_ANALYSIS_DISPATCH_FAILED" ->
            SubmitResult.Unavailable("분석 서버가 아직 준비되지 않았어요. 녹음은 서버에 보관됐으니 준비되면 다시 요청해 주세요.")
        e.code == "VOICE_ANALYSIS_IN_PROGRESS" ->
            SubmitResult.Accepted(meta.serverAnalysisId, "이미 이 녹음의 분석이 진행 중이에요. 결과는 리포트에서 확인할 수 있어요.")
        e.code == "RECORDING_NOT_READY" -> SubmitResult.Failed("서버가 아직 녹음 업로드를 마무리하고 있어요. 잠시 후 다시 요청해 주세요.")
        e.code == "RECORDING_NOT_FOUND" -> {
            save(meta.copy(serverRecordingId = null, serverAnalysisId = null))
            SubmitResult.Failed("서버에서 녹음을 찾지 못했어요. 다시 요청하면 새로 올려요.")
        }
        else -> SubmitResult.Failed("분석 요청 실패: ${e.message}")
    }

    companion object {
        /** 백엔드 기본 `VOICE_RECORDING_MAX_SIZE=25MB`(spring.servlet.multipart.max-file-size). 16 kHz PCM16 모노로 약 13분. */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        private const val LOGIN_REQUIRED = "로그인하면 서버 분석을 요청할 수 있어요. 녹음은 이 기기에 저장됐어요."
        private const val NETWORK = "서버에 연결할 수 없어요. 네트워크를 확인하고 다시 요청해 주세요."
    }
}
