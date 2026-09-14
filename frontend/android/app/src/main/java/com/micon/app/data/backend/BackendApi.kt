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
import com.micon.app.data.backend.dto.RecordingUploadDto
import com.micon.app.data.backend.dto.VoiceAnalysisCreateRequest
import com.micon.app.data.backend.dto.VoiceAnalysisDto
import com.micon.app.data.backend.dto.VoiceAnalysisReportDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.Part
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

    // ---- 음성 녹음·분석 (RecordingController · VoiceAnalysisController) ----

    /** 녹음 업로드. `file` 은 audio/wav·x-wav·mpeg·mp4, 기본 25MB 까지. `keepOriginal` 은 서버 기본(원본 보관 정책)을 따르려면 생략. 201 → recordingId. */
    @Multipart
    @POST("recordings")
    suspend fun uploadRecording(
        @Part file: MultipartBody.Part,
        @Part("attemptNo") attemptNo: RequestBody,
        @Part("keepOriginal") keepOriginal: RequestBody? = null,
    ): Response<ApiResponse<RecordingUploadDto>>

    /** 분석 생성. AI 연동이 꺼져 있으면 503 VOICE_ANALYSIS_DISPATCH_FAILED, 같은 녹음 분석 중이면 409 VOICE_ANALYSIS_IN_PROGRESS. */
    @POST("voice-analyses")
    suspend fun createVoiceAnalysis(@Body body: VoiceAnalysisCreateRequest): Response<ApiResponse<VoiceAnalysisDto>>

    @GET("voice-analyses/{analysisId}")
    suspend fun voiceAnalysis(@Path("analysisId") analysisId: Long): Response<ApiResponse<VoiceAnalysisDto>>

    /** 가장 최근 분석. 없으면 200 에 data null. */
    @GET("voice-analyses/latest")
    suspend fun latestVoiceAnalysis(): Response<ApiResponse<VoiceAnalysisDto>>

    @GET("voice-analyses/{analysisId}/report")
    suspend fun voiceAnalysisReport(@Path("analysisId") analysisId: Long): Response<ApiResponse<VoiceAnalysisReportDto>>
}
