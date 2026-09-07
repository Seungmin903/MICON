package com.micon.app.data.backend

import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.RefreshTokenRequest
import com.micon.app.data.backend.dto.TokenPair
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 앱 백엔드 REST API (예정). 경로는 BuildConfig.API_BASE_URL(…/api/v1/) 기준 상대 경로.
 * 계약이 정해지면 micon_schema.sql 의 사용자 쪽 8개 테이블(users, favorites, setlists, practice_sessions,
 * user_voice_profiles, song_fit …)에 맞춰 엔드포인트를 추가한다. 곡 카탈로그 API 가 생기면
 * data/SongRepository 의 Retrofit 구현도 여기서 만든다.
 */
interface BackendApi {

    @GET("health")
    suspend fun health(): Response<ApiResponse<Any>>

    /** TokenAuthenticator 가 OkHttp 스레드에서 동기 호출하므로 Call 로 둔다. */
    @POST("auth/refresh")
    fun refreshTokenSync(@Body body: RefreshTokenRequest): Call<ApiResponse<TokenPair>>

    // TODO: auth/oauth/login, members/me, favorites, setlists, practice-sessions, voice-profile …
}
