package com.micon.app.data.recommend

import com.micon.app.data.recommend.dto.RecommendJobDto
import com.micon.app.data.recommend.dto.RecommendStatusDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * 추천 서버 REST (ai/recommend-server/README.md "API"). 운영에서는 BE 가 이 서버를 부르고 앱은 BE 만 보지만,
 * 개발 중에는 앱이 10.0.2.2:8788 로 직접 붙는다. 응답 모양은 같다.
 */
interface RecommendApi {
    @GET("recommendations/status")
    suspend fun status(): RecommendStatusDto

    /** 보컬 녹음 업로드 → 202 job. 이후 [job] 으로 폴링. genres·preferredArtists 는 같은 이름 파트를 반복해 보낸다. */
    @Multipart
    @POST("recommendations")
    suspend fun create(
        @Part audio: MultipartBody.Part,
        @Part("topK") topK: RequestBody,
        @Part("mode") mode: RequestBody,
        @Part genres: List<MultipartBody.Part>,
        @Part preferredArtists: List<MultipartBody.Part>,
    ): RecommendJobDto

    @GET("recommendations/{id}")
    suspend fun job(@Path("id") id: String): RecommendJobDto
}
