package com.micon.app.data.engine

import com.micon.app.data.engine.dto.Comparison
import com.micon.app.data.engine.dto.ErrorDetail
import com.micon.app.data.engine.dto.Job
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 엔진 클라이언트 팩토리 + 편의 함수. Moshi 는 리플렉션 어댑터(KSP/직렬화 플러그인 불필요).
 * baseUrl 은 반드시 `/` 로 끝나야 한다 (예: http://10.0.2.2:8787/).
 */
class EngineClient(val baseUrl: String, logging: Boolean = true) {
    val moshi: Moshi = EngineClient.moshi

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 업로드·분리 대기
        .writeTimeout(120, TimeUnit.SECONDS)
        .apply {
            if (logging) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .build()

    val api: EngineApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(EngineApi::class.java)

    /** 반주 스트리밍 URL (ExoPlayer 에 그대로). semitones=0 이면 원키. */
    fun instrumentalUrl(jobId: String, semitones: Int = 0): String =
        "${baseUrl}jobs/$jobId/files/instrumental" + if (semitones != 0) "?semitones=$semitones" else ""

    /** 녹음 파일을 올려 채점 시작. m4a/wav/webm 모두 가능. */
    suspend fun startCompare(
        referenceJobId: String,
        audio: File,
        sourceType: String = "vocal",
        allowTranspose: Boolean = true,
        octaveTolerant: Boolean = true,
    ): Job {
        val mime = when (audio.extension.lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val part = MultipartBody.Part.createFormData("audio", audio.name, audio.asRequestBody(mime.toMediaType()))
        val text = "text/plain".toMediaType()
        return api.compare(
            referenceJobId, part,
            sourceType.toRequestBody(text),
            allowTranspose.toString().toRequestBody(text),
            octaveTolerant.toString().toRequestBody(text),
        )
    }

    /**
     * job 이 complete/failed 가 될 때까지 폴링. 진행률은 [onProgress] 로 알린다.
     * 실패하면 EngineException.
     */
    suspend fun awaitJob(jobId: String, intervalMs: Long = 1400, onProgress: (Job) -> Unit = {}): Job {
        while (true) {
            val job = api.job(jobId)
            onProgress(job)
            if (job.isFailed) throw EngineException(job.error ?: "분석에 실패했습니다.")
            if (job.isComplete) return job
            delay(intervalMs)
        }
    }

    /** 채점 job 을 끝까지 기다려 comparison 을 돌려준다. */
    suspend fun awaitComparison(performanceJobId: String, onProgress: (Job) -> Unit = {}): Comparison {
        val job = awaitJob(performanceJobId, onProgress = onProgress)
        return job.result?.comparison ?: throw EngineException("채점 결과(comparison)가 없습니다.")
    }

    /** HttpException 의 FastAPI `detail` 을 사람이 읽을 메시지로. */
    fun describe(error: Throwable): String = when (error) {
        is HttpException -> {
            val body = error.response()?.errorBody()?.string()
            val detail = body?.let { runCatching { moshi.adapter(ErrorDetail::class.java).fromJson(it)?.detail }.getOrNull() }
            detail ?: "엔진 오류 (HTTP ${error.code()})"
        }
        is EngineException -> error.message ?: "엔진 오류"
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
            "엔진에 연결할 수 없습니다 ($baseUrl). 엔진이 켜져 있는지 확인하세요."
        else -> error.message ?: error.toString()
    }

    companion object {
        val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }
}

class EngineException(message: String) : RuntimeException(message)
