package com.micon.app.data.voice

import com.micon.app.core.network.BackendClient
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.VoiceAnalysisCreateRequest
import com.micon.app.data.backend.dto.VoiceAnalysisDto
import com.micon.app.data.backend.dto.VoiceAnalysisReportDto
import com.micon.app.data.engine.EngineClient
import com.squareup.moshi.Types
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import retrofit2.Response
import java.io.File
import java.util.concurrent.TimeUnit

/** 백엔드 `VoiceAnalysisStatus`. 모르는 값은 [UNKNOWN] 으로 읽어 앱이 서버보다 먼저 깨지지 않게 한다. */
enum class VoiceAnalysisStatus {
    READY, RECORDING, UPLOADING, ANALYZING, COMPLETED, FAILED, CANCELED, INSUFFICIENT_DATA, UNKNOWN;

    /** 더 기다려도 바뀌지 않는 상태 */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED || this == INSUFFICIENT_DATA

    companion object {
        fun parse(raw: String?): VoiceAnalysisStatus = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

data class VoiceAnalysis(
    val id: Long,
    val recordingId: Long?,
    val songId: Long?,
    /** SCALE | SONG */
    val method: String?,
    val status: VoiceAnalysisStatus,
    val qualityStatus: String?,
    val failureCode: String?,
    val failureReason: String?,
    /** ISO LocalDateTime 문자열(서버 시각) */
    val createdAt: String?,
    val completedAt: String?,
)

/** 음역 분석 결과. MIDI 는 서버 BigDecimal 그대로(Double). */
data class VocalRange(
    val fullLowMidi: Double?,
    val fullHighMidi: Double?,
    val stableLowMidi: Double?,
    val stableHighMidi: Double?,
    val pitchMedianMidi: Double?,
    val pitchAccuracyScore: Double?,
    val pitchStabilityScore: Double?,
    val confidence: Double?,
    val measuredAt: String?,
)

data class VocalTimbre(
    val label: String?,
    val rhythmScore: Double?,
    val pronunciationScore: Double?,
    val confidence: Double?,
    val explanation: String?,
    val coreLowMidi: Double?,
    val coreHighMidi: Double?,
    val medianMidi: Double?,
)

data class VoiceAnalysisReport(val analysis: VoiceAnalysis, val range: VocalRange?, val timbre: VocalTimbre?)

/** 백엔드가 `{code,message}` 봉투로 거절했을 때. [code] 는 ErrorCode 이름(예: VOICE_ANALYSIS_DISPATCH_FAILED). */
class VoiceApiException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * 음성 녹음 업로드·분석 요청·결과 조회. 백엔드 `RecordingController`·`VoiceAnalysisController` 만 본다(AI 서버 직접 호출 없음).
 *
 * 흐름: [upload] → recordingId → [create] → analysisId → [get] 폴링(상태) → COMPLETED 면 [report].
 * 리포트 탭은 [latest] 로 가장 최근 분석을 찾는다.
 */
interface VoiceAnalysisRepository {
    /**
     * 녹음 파일 업로드. 백엔드 허용 MIME: audio/wav, audio/x-wav, audio/mpeg, audio/mp4. 기본 최대 25MB(초과 시 400 INVALID_RECORDING_FILE 또는 413).
     * @param onProgress 0~1. 업로드가 재시도되면 0 부터 다시 올라갈 수 있다.
     * @return recordingId
     */
    suspend fun upload(file: File, mimeType: String = "audio/wav", attemptNo: Int = 1, onProgress: (Float) -> Unit = {}): Long

    /** 분석 생성. AI 연동이 꺼져 있으면 503 VOICE_ANALYSIS_DISPATCH_FAILED — 정상적인 "미준비" 상태다. */
    suspend fun create(recordingId: Long, songId: Long?, method: String = METHOD_SONG): VoiceAnalysis

    suspend fun get(analysisId: Long): VoiceAnalysis

    /** 가장 최근 분석. 하나도 없으면 null(서버가 data: null 로 준다). */
    suspend fun latest(): VoiceAnalysis?

    suspend fun report(analysisId: Long): VoiceAnalysisReport

    companion object {
        const val METHOD_SONG = "SONG"
        const val METHOD_SCALE = "SCALE"
    }
}

class RemoteVoiceAnalysisRepository(
    private val api: BackendApi,
    /** 업로드 전용(긴 write timeout). 테스트에서는 [api] 와 같아도 된다. */
    private val uploadApi: BackendApi = api,
) : VoiceAnalysisRepository {

    override suspend fun upload(file: File, mimeType: String, attemptNo: Int, onProgress: (Float) -> Unit): Long {
        val body = ProgressRequestBody(file, mimeType.toMediaType(), onProgress)
        val part = MultipartBody.Part.createFormData("file", file.name, body)
        val response = uploadApi.uploadRecording(part, attemptNo.toString().toRequestBody(TEXT))
        val id = response.dataOrThrow().recordingId
        if (id <= 0) throw VoiceApiException(response.code(), null, "서버가 녹음 id 를 주지 않았어요.")
        return id
    }

    override suspend fun create(recordingId: Long, songId: Long?, method: String): VoiceAnalysis =
        api.createVoiceAnalysis(VoiceAnalysisCreateRequest(recordingId, method, songId)).dataOrThrow().toModel()

    override suspend fun get(analysisId: Long): VoiceAnalysis = api.voiceAnalysis(analysisId).dataOrThrow().toModel()

    override suspend fun latest(): VoiceAnalysis? {
        val response = api.latestVoiceAnalysis()
        if (!response.isSuccessful) throw response.toException()
        return response.body()?.data?.takeIf { it.analysisId > 0 }?.toModel()
    }

    override suspend fun report(analysisId: Long): VoiceAnalysisReport {
        val dto = api.voiceAnalysisReport(analysisId).dataOrThrow()
        val analysis = (dto.analysis ?: VoiceAnalysisDto(analysisId = analysisId)).toModel()
        return VoiceAnalysisReport(analysis, dto.range?.toModel(), dto.timbre?.toModel())
    }

    companion object {
        private val TEXT = "text/plain".toMediaType()

        /** 앱 컨테이너용: 업로드는 25MB 까지 느린 회선에서도 끊기지 않게 write timeout 을 늘린 클라이언트를 쓴다. */
        fun forClient(client: BackendClient): RemoteVoiceAnalysisRepository {
            val slow = client.okHttp.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val uploadApi = client.retrofit.newBuilder().client(slow).build().create(BackendApi::class.java)
            return RemoteVoiceAnalysisRepository(client.api, uploadApi)
        }

        private fun <T> Response<ApiResponse<T>>.dataOrThrow(): T {
            if (!isSuccessful) throw toException()
            return body()?.data ?: throw VoiceApiException(code(), body()?.code, "서버 응답에 데이터가 없어요.")
        }

        /** `{code,message,data}` 봉투. ApiResponse 는 제네릭이라 raw Class 로는 Moshi 가 읽지 못해 파라미터 타입을 명시한다. */
        private val envelopeAdapter = EngineClient.moshi.adapter<ApiResponse<Any>>(Types.newParameterizedType(ApiResponse::class.java, Any::class.java))

        private fun Response<*>.toException(): VoiceApiException {
            val envelope = runCatching {
                errorBody()?.string()?.takeIf { it.isNotBlank() }?.let(envelopeAdapter::fromJson)
            }.getOrNull()
            val message = envelope?.message?.takeIf { it.isNotBlank() } ?: "서버 오류 (HTTP ${code()})"
            return VoiceApiException(code(), envelope?.code?.takeIf { it.isNotBlank() }, message)
        }

        private fun VoiceAnalysisDto.toModel() = VoiceAnalysis(
            id = analysisId, recordingId = recordingId, songId = songId, method = method,
            status = VoiceAnalysisStatus.parse(status), qualityStatus = qualityStatus,
            failureCode = failureCode, failureReason = failureReason, createdAt = createdAt, completedAt = completedAt,
        )

        private fun com.micon.app.data.backend.dto.VocalRangeDto.toModel() = VocalRange(
            fullLowMidi, fullHighMidi, stableLowMidi, stableHighMidi, pitchMedianMidi,
            pitchAccuracyScore, pitchStabilityScore, confidence, measuredAt,
        )

        private fun com.micon.app.data.backend.dto.VocalTimbreDto.toModel() = VocalTimbre(
            timbreLabel, rhythmScore, pronunciationScore, confidence, explanation,
            observedCoreLowMidi, observedCoreHighMidi, observedMedianMidi,
        )
    }
}

/** 파일을 64KB 씩 흘려보내며 진행률을 알린다. OkHttp 가 재시도하면 writeTo 가 다시 불려 0 부터 시작한다. */
private class ProgressRequestBody(
    private val file: File,
    private val mediaType: MediaType,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var written = 0L
        val buffer = Buffer()
        file.source().use { source ->
            while (true) {
                val read = source.read(buffer, CHUNK)
                if (read == -1L) break
                sink.write(buffer, read)
                written += read
                onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
            }
        }
    }

    private companion object { const val CHUNK = 64L * 1024 }
}
