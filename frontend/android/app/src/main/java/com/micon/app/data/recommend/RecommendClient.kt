package com.micon.app.data.recommend

import com.micon.app.data.engine.EngineClient
import com.micon.app.data.recommend.dto.RecommendJobDto
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/** 추천 서버 호출 추상화. 실제 구현은 [RecommendClient], 테스트는 픽스처를 돌려주는 가짜. */
interface RecommendGateway {
    suspend fun recommend(audio: File, topK: Int, mode: String, genres: List<String>, preferredArtists: List<String> = emptyList()): RecommendJobDto
    /** complete/failed 까지 폴링. 실패면 [RecommendException]. */
    suspend fun awaitJob(jobId: String): RecommendJobDto
}

/** 추천 서버(8788) 클라이언트. 구조는 [EngineClient] 와 같다. baseUrl 은 `/` 로 끝나야 한다. */
class RecommendClient(val baseUrl: String, logging: Boolean = true) : RecommendGateway {
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)      // 녹음 업로드
        .apply { if (logging) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) }
        .build()

    val api: RecommendApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(EngineClient.moshi))
        .build()
        .create(RecommendApi::class.java)

    override suspend fun recommend(audio: File, topK: Int, mode: String, genres: List<String>, preferredArtists: List<String>): RecommendJobDto {
        val mime = when (audio.extension.lowercase()) { "wav" -> "audio/wav"; "m4a", "mp4" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; else -> "application/octet-stream" }
        val text = "text/plain".toMediaType()
        return api.create(
            MultipartBody.Part.createFormData("audio", audio.name, audio.asRequestBody(mime.toMediaType())),
            topK.toString().toRequestBody(text),
            mode.toRequestBody(text),
            genres.map { MultipartBody.Part.createFormData("genres", it) },
            preferredArtists.map { MultipartBody.Part.createFormData("preferredArtists", it) },
        )
    }

    override suspend fun awaitJob(jobId: String): RecommendJobDto {
        repeat(MAX_POLLS) {
            val job = api.job(jobId)
            if (job.isFailed) throw RecommendException(job.error ?: job.message ?: "추천에 실패했습니다.")
            if (job.isComplete) return job
            delay(POLL_MS)
        }
        throw RecommendException("추천 계산이 너무 오래 걸립니다. 잠시 후 다시 시도해 주세요.")
    }

    private companion object {
        const val POLL_MS = 1500L
        const val MAX_POLLS = 80          // ≈ 2분
    }
}

class RecommendException(message: String) : RuntimeException(message)
