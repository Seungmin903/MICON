package com.micon.app.range

import com.micon.app.data.engine.EngineClient
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

/** Local model preview for the test APK. Account authentication is independent of model analysis. */
class RangePreviewClient(baseUrl: String) {
    interface Api {
        @Multipart @POST("preview/guided-range")
        suspend fun analyze(@Part audio: MultipartBody.Part, @Part("metadata") metadata: RequestBody): RangeReport
    }
    val api: Api = Retrofit.Builder().baseUrl(baseUrl)
        .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES).writeTimeout(60, TimeUnit.SECONDS).build())
        .addConverterFactory(MoshiConverterFactory.create(EngineClient.moshi)).build().create(Api::class.java)
}
