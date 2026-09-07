package com.micon.app.core.network

import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.RefreshTokenRequest
import com.micon.app.data.engine.EngineClient
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 앱 백엔드(Spring/MySQL) 클라이언트. 엔진 클라이언트(data/engine/EngineClient)와는 별개다.
 *
 * - AuthInterceptor: 보호 API 에만 `Authorization: Bearer` 를 붙이고 공개 API 에서는 제거한다.
 * - TokenAuthenticator: 401 이 오면 Refresh Token 으로 한 번만 재발급 후 재시도한다.
 *   동시 401 은 lock 으로 직렬화해 Refresh Token Rotation 이 중복 수행되지 않게 한다.
 * baseUrl 은 `/` 로 끝나야 한다 (예: http://10.0.2.2:8080/api/v1/).
 */
class BackendClient(val baseUrl: String, logging: Boolean = true) {

    /** 토큰 없이 호출해야 하는 공개 API 경로(base path 이후 부분) */
    private val publicPaths = setOf("auth/oauth/login", "auth/oauth/signup", "auth/refresh", "health")

    private fun isPublic(request: Request): Boolean {
        val path = request.url.encodedPath
        return publicPaths.any { path.endsWith("/$it") }
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        if (isPublic(original)) {
            builder.removeHeader("Authorization")
        } else {
            TokenManager.getAccessToken()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        }
        chain.proceed(builder.build())
    }

    private val refreshLock = Any()

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (isPublic(response.request)) return null
            if (response.priorResponse != null) return null   // 이미 한 번 재시도한 요청

            val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            synchronized(refreshLock) {
                val current = TokenManager.getAccessToken()
                if (!current.isNullOrBlank() && current != failedToken) {
                    return response.request.newBuilder().header("Authorization", "Bearer $current").build()
                }
                val refreshToken = TokenManager.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    SessionEvents.notifyExpired(); return null
                }
                val tokens = runCatching { api.refreshTokenSync(RefreshTokenRequest(refreshToken)).execute() }
                    .getOrNull()?.takeIf { it.isSuccessful }?.body()?.data
                if (tokens == null) {
                    TokenManager.clear(); SessionEvents.notifyExpired(); return null
                }
                TokenManager.saveTokens(tokens.accessToken, tokens.refreshToken)
                return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
            }
        }
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .apply {
            if (logging) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("Authorization")   // 토큰은 로그에 남기지 않는다
                    },
                )
            }
        }
        .build()

    val api: BackendApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(EngineClient.moshi))
        .build()
        .create(BackendApi::class.java)
}
