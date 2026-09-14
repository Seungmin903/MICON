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
import com.micon.app.data.backend.dto.PracticeCompleteRequest
import com.micon.app.data.backend.dto.PracticeCreateRequest
import com.micon.app.data.backend.dto.PracticeDetailDto
import com.micon.app.data.backend.dto.PracticeReportDto
import com.micon.app.data.backend.dto.PracticeResultSaveRequest
import com.micon.app.data.backend.dto.PracticeStatusRequest
import com.micon.app.data.backend.dto.PracticeSummaryDto
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
import retrofit2.http.PATCH
import retrofit2.http.PUT
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

    // ---- 연습 기록 (PracticeController) ----

    /** 내 연습 목록, 최신순. size 1~100. */
    @GET("practices")
    suspend fun practices(@Query("page") page: Int, @Query("size") size: Int): Response<ApiResponse<PageDto<PracticeSummaryDto>>>

    /** 세션 생성(READY). 곡·반주(선택 키)가 준비돼야 한다: 409 PRACTICE_ASSET_NOT_READY, 422 UNSUPPORTED_PRACTICE_KEY. */
    @POST("practices")
    suspend fun createPractice(@Body body: PracticeCreateRequest): Response<ApiResponse<PracticeDetailDto>>

    @GET("practices/{practiceId}")
    suspend fun practice(@Path("practiceId") practiceId: Long): Response<ApiResponse<PracticeDetailDto>>

    /** READY→IN_PROGRESS 등 진행 상태 전환. 허용되지 않는 전환(같은 상태 포함)은 409 INVALID_PRACTICE_STATE. */
    @PATCH("practices/{practiceId}/status")
    suspend fun updatePracticeStatus(@Path("practiceId") practiceId: Long, @Body body: PracticeStatusRequest): Response<ApiResponse<PracticeDetailDto>>

    /** 진행 상태(RECORDING·IN_PROGRESS·PAUSED)에서만 COMPLETED 로. */
    @POST("practices/{practiceId}/complete")
    suspend fun completePractice(@Path("practiceId") practiceId: Long, @Body body: PracticeCompleteRequest): Response<ApiResponse<PracticeDetailDto>>

    /** READY~PAUSED → ABORTED. */
    @POST("practices/{practiceId}/cancel")
    suspend fun cancelPractice(@Path("practiceId") practiceId: Long): Response<ApiResponse<PracticeDetailDto>>

    /** COMPLETED 세션에 라이브 채점 점수 저장(재시도 시 새 결과 행). */
    @PUT("practices/{practiceId}/performance")
    suspend fun savePracticePerformance(@Path("practiceId") practiceId: Long, @Body body: PracticeResultSaveRequest): Response<ApiResponse<PracticeReportDto>>

    /** 저장된 결과. 없으면 404 PRACTICE_RESULT_NOT_FOUND. */
    @GET("practices/{practiceId}/report")
    suspend fun practiceReport(@Path("practiceId") practiceId: Long): Response<ApiResponse<PracticeReportDto>>

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
