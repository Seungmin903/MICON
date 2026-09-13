package com.micon.app.data.backend

import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.AuthResponse
import com.micon.app.data.backend.dto.CurrentUser
import com.micon.app.data.backend.dto.EmailAuthRequest
import com.micon.app.data.backend.dto.PageDto
import com.micon.app.data.backend.dto.PracticeAssetsDto
import com.micon.app.data.backend.dto.SongDetailDto
import com.micon.app.data.backend.dto.SongSummaryDto
import com.micon.app.data.backend.dto.TokenPair
import com.micon.app.data.backend.dto.PasswordResetIssueRequest
import com.micon.app.data.backend.dto.PasswordResetIssued
import com.micon.app.data.backend.dto.PasswordResetVerifyRequest
import com.micon.app.data.backend.dto.PasswordResetVerified
import com.micon.app.data.backend.dto.PasswordResetChangeRequest
import com.micon.app.data.backend.dto.WithdrawRequest
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.HTTP
import retrofit2.http.Path
import retrofit2.http.Query

/** Paths are relative to the /api/v1/ backend base URL. */
interface BackendApi {
    @GET("health")
    suspend fun health(): Response<ApiResponse<Any>>

    @POST("auth/login")
    suspend fun login(@Body body: EmailAuthRequest): Response<ApiResponse<AuthResponse>>

    @POST("auth/signup")
    suspend fun signup(@Body body: EmailAuthRequest): Response<ApiResponse<AuthResponse>>

    @GET("users/me")
    suspend fun currentUser(): Response<ApiResponse<CurrentUser>>

    @POST("auth/logout")
    suspend fun logout(): Response<ApiResponse<Any>>

    @POST("auth/password-reset-requests")
    suspend fun requestPasswordReset(@Body body: PasswordResetIssueRequest): Response<ApiResponse<PasswordResetIssued>>

    @POST("auth/password-reset-verifications")
    suspend fun verifyPasswordReset(@Body body: PasswordResetVerifyRequest): Response<ApiResponse<PasswordResetVerified>>

    @POST("auth/password-resets")
    suspend fun resetPassword(
        @Header("X-Password-Reset-Token") resetToken: String,
        @Body body: PasswordResetChangeRequest,
    ): Response<ApiResponse<Any>>

    @HTTP(method = "DELETE", path = "users/me", hasBody = true)
    suspend fun withdraw(@Body body: WithdrawRequest): Response<ApiResponse<Any>>

    /** Refresh has no JSON body; its bearer credential is the refresh token. */
    @POST("auth/refresh")
    fun refreshTokenSync(@Header("Authorization") authorization: String): Call<ApiResponse<TokenPair>>

    // ---- 곡 카탈로그 (SongController · PracticeAssetController) ----

    /** 제목·가수 검색. size 최대 100. */
    @GET("songs")
    suspend fun songs(@Query("query") query: String, @Query("page") page: Int, @Query("size") size: Int): Response<ApiResponse<PageDto<SongSummaryDto>>>

    @GET("songs/{songId}")
    suspend fun song(@Path("songId") songId: Long): Response<ApiResponse<SongDetailDto>>

    /** 반주·목표 음정·가사·구간. 준비 안 됨 409, 미지원 키 422. */
    @GET("songs/{songId}/practice-assets")
    suspend fun practiceAssets(@Path("songId") songId: Long, @Query("selectedKey") selectedKey: Int = 0): Response<ApiResponse<PracticeAssetsDto>>
}
