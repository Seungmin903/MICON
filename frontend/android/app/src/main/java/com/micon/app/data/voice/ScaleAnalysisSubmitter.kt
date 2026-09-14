package com.micon.app.data.voice

import java.io.File
import java.io.IOException

/** 간편 분석 녹음 제출 결과. */
sealed interface ScaleSubmitResult {
    /** 접수됨. 결과는 리포트 탭이 `GET /voice-analyses/latest` 로 읽는다. */
    data class Accepted(val analysisId: Long, val recordingId: Long) : ScaleSubmitResult
    /** 지금은 서버 분석을 요청할 수 없는 정상 상태(비회원, AI 연동 꺼짐). */
    data class Unavailable(val message: String) : ScaleSubmitResult
    data class Failed(val message: String) : ScaleSubmitResult
}

/**
 * 간편 분석(기준음 5개) 녹음을 서버 분석에 넘긴다: `POST /recordings` → `POST /voice-analyses{method: SCALE}`.
 * 화면은 확인된 5음으로 만든 임시 프로필을 먼저 보여 주고, 서버 결과(COMPLETED)가 오면 리포트 탭이 그것으로 덮는다.
 * 비회원은 서버를 부르지 않는다. 분석 생성이 503(`VOICE_ANALYSIS_DISPATCH_FAILED`, AI 연동 꺼짐)이면 녹음은 서버에 남고 "미준비" 로 알린다.
 */
class ScaleAnalysisSubmitter(
    private val voice: VoiceAnalysisRepository,
    private val isLoggedIn: () -> Boolean,
) {
    suspend fun submit(wav: File, onProgress: (String) -> Unit = {}): ScaleSubmitResult {
        if (!isLoggedIn()) return ScaleSubmitResult.Unavailable(LOGIN_REQUIRED)
        if (!wav.isFile || wav.length() <= WAV_HEADER_BYTES) return ScaleSubmitResult.Failed("녹음 파일이 없어요. 다시 측정해 주세요.")

        onProgress("녹음을 올리고 있어요")
        val recordingId = try {
            voice.upload(wav, attemptNo = 1) { p -> onProgress("녹음을 올리고 있어요 ${(p * 100).toInt()}%") }
        } catch (e: VoiceApiException) {
            return if (e.status == 401) ScaleSubmitResult.Unavailable(LOGIN_REQUIRED) else ScaleSubmitResult.Failed("업로드 실패: ${e.message}")
        } catch (e: IOException) {
            return ScaleSubmitResult.Failed(NETWORK)
        }

        onProgress("서버 분석을 요청하고 있어요")
        return try {
            val analysis = voice.create(recordingId, songId = null, method = VoiceAnalysisRepository.METHOD_SCALE)
            ScaleSubmitResult.Accepted(analysis.id, recordingId)
        } catch (e: VoiceApiException) {
            when {
                e.status == 401 -> ScaleSubmitResult.Unavailable(LOGIN_REQUIRED)
                e.code == "VOICE_ANALYSIS_DISPATCH_FAILED" -> ScaleSubmitResult.Unavailable("분석 서버가 아직 준비되지 않았어요. 녹음은 서버에 보관됐어요.")
                else -> ScaleSubmitResult.Failed("분석 요청 실패: ${e.message}")
            }
        } catch (e: IOException) {
            ScaleSubmitResult.Failed(NETWORK)
        }
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44L
        private const val LOGIN_REQUIRED = "로그인하면 서버가 목소리를 정밀 분석해요. 지금은 이 기기의 측정 결과만 보여 드려요."
        private const val NETWORK = "서버에 연결할 수 없어 정밀 분석을 요청하지 못했어요. 측정 결과는 리포트에 남아요."
    }
}
