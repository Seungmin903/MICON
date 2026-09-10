package com.micon.app.data.backend

import com.micon.app.data.backend.dto.ApiResponse
import com.micon.app.data.backend.dto.AuthResponse
import com.micon.app.data.backend.dto.CurrentUser
import com.micon.app.data.backend.dto.EmailAuthRequest
import com.micon.app.data.backend.dto.TokenPair
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

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

    /** Refresh has no JSON body; its bearer credential is the refresh token. */
    @POST("auth/refresh")
    fun refreshTokenSync(@Header("Authorization") authorization: String): Call<ApiResponse<TokenPair>>
}
