package com.micon.app.data.engine

import com.micon.app.data.engine.dto.HealthResponse
import com.micon.app.data.engine.dto.InstrumentalKeyResponse
import com.micon.app.data.engine.dto.Job
import com.micon.app.data.engine.dto.JobsResponse
import com.micon.app.data.engine.dto.CompareOptions
import com.micon.app.data.engine.dto.SemitonesRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * VocalScope 엔진 REST API (FastAPI). 라우트 원문은 docs/reference-src/app.py L126–407, 요약은
 * docs/ANDROID_START_GUIDE.md §3. 앱이 쓰는 엔드포인트만 선언한다.
 *
 * job id: 카탈로그 곡은 `tj-<TJ번호>`(= songs.song_id), 업로드/채점은 UUID.
 */
interface EngineApi {
    /** 연결 확인 */
    @GET("health")
    suspend fun health(): HealthResponse

    /** 완료된 작업 최신순 (최대 200) */
    @GET("jobs")
    suspend fun jobs(@Query("kind") kind: String = "reference", @Query("limit") limit: Int = 50): JobsResponse

    /** 진행률 폴링(1–2 s) 및 결과 읽기 */
    @GET("jobs/{id}")
    suspend fun job(@Path("id") id: String): Job

    /**
     * 부른 노래 채점 시작 → 202 performance job(kind=performance). 이후 [job] 으로 폴링해
     * `result.comparison` 을 읽는다.
     * @param sourceType "vocal" = 반주 없이 부른 녹음(헤드폰) → 분리 생략(빠름), "mix" = 반주 섞임 → 분리
     */
    @Multipart
    @POST("jobs/{id}/compare")
    suspend fun compare(
        @Path("id") referenceJobId: String,
        @Part audio: MultipartBody.Part,
        @Part("sourceType") sourceType: RequestBody,
        @Part("allowTranspose") allowTranspose: RequestBody,
        @Part("octaveTolerant") octaveTolerant: RequestBody,
    ): Job

    /** 옵션만 바꿔 재채점 (오디오 재분석 없음) */
    @POST("jobs/{id}/comparison")
    suspend fun rescore(@Path("id") performanceJobId: String, @Body options: CompareOptions): Job

    /** 키 변경 반주 미리 렌더 (−6…+5, 6분 곡 ≈9 s). 이후 instrumental 스트리밍 URL 에 semitones 를 붙인다. */
    @POST("jobs/{id}/instrumental-key")
    suspend fun renderInstrumentalKey(@Path("id") jobId: String, @Body body: SemitonesRequest): InstrumentalKeyResponse
}
