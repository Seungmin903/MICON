package com.micon.app.core.network

import com.micon.app.core.auth.AuthTokenStore
import com.micon.app.core.auth.SessionSnapshot
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.onboarding.VoiceSessionAccess
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackendClient(
    val baseUrl: String,
    logging: Boolean = true,
    private val tokens: AuthTokenStore = TokenManager,
    private val onSessionExpired: () -> Unit = { SessionEvents.notifyExpired() },
) {
    private fun isRefresh(request: Request) = request.url.encodedPath.endsWith("/auth/refresh")

    private fun isPublic(request: Request): Boolean {
        val path = request.url.encodedPath
        return request.tag(VoiceSessionAccess::class.java)?.let { it.ownerId == null } == true ||
            (request.method == "GET" && (path.endsWith("/songs") || Regex(".*/songs/[^/]+/practice-assets").matches(path))) ||
            path.endsWith("/health") ||
            (request.method == "POST" && (path.endsWith("/auth/login") || path.endsWith("/auth/signup")))
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        val voiceAccess = request.tag(VoiceSessionAccess::class.java)
        builder.removeHeader("X-Guest-Session-Token")
        if (voiceAccess?.ownerId == null) voiceAccess?.guestToken?.let { builder.header("X-Guest-Session-Token", it) }
        if (isPublic(request)) {
            builder.removeHeader("Authorization")
        } else if (!isRefresh(request)) {
            val session = tokens.snapshot()
            if (voiceAccess != null && (voiceAccess.ownerId != session.userId || session.accessToken.isNullOrBlank())) {
                throw IOException("Voice session owner changed")
            }
            builder.tag(SessionSnapshot::class.java, session).removeHeader("Authorization")
            session.accessToken?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        }
        chain.proceed(builder.build())
    }

    private val refreshLock = Any()

    private fun expire(session: SessionSnapshot) {
        if (tokens.clearIfCurrent(session)) onSessionExpired()
    }

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (isPublic(response.request) || isRefresh(response.request)) return null
            val sent = response.request.tag(SessionSnapshot::class.java) ?: return null
            synchronized(refreshLock) {
                val current = tokens.snapshot()
                if (sent.generation != current.generation) return null
                if (response.priorResponse != null) {
                    expire(current)
                    return null
                }
                if (!current.accessToken.isNullOrBlank() && current.accessToken != sent.accessToken) {
                    return response.request.newBuilder().tag(SessionSnapshot::class.java, current)
                        .header("Authorization", "Bearer ${current.accessToken}").build()
                }
                val refreshToken = current.refreshToken
                if (refreshToken.isNullOrBlank()) {
                    expire(current)
                    return null
                }
                // Network/5xx failures remain retryable instead of signing the user out.
                val refreshed = api.refreshTokenSync("Bearer $refreshToken").execute()
                if (!refreshed.isSuccessful) {
                    if (refreshed.code() in listOf(400, 401, 403)) {
                        expire(current)
                        return null
                    }
                    throw IOException("Session refresh temporarily unavailable")
                }
                val pair = refreshed.body()?.data
                if (pair == null || pair.accessToken.isBlank() || pair.refreshToken.isBlank()) {
                    throw IOException("Invalid session refresh response")
                }
                if (!tokens.rotateTokens(current, pair.accessToken, pair.refreshToken)) return null
                return response.request.newBuilder().tag(SessionSnapshot::class.java, tokens.snapshot())
                    .header("Authorization", "Bearer ${pair.accessToken}").build()
            }
        }
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor).authenticator(tokenAuthenticator)
        .apply {
            if (logging) addInterceptor(HttpLoggingInterceptor().apply {
                // Passwords and issued tokens are in bodies: never log bodies, even in debug.
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("X-Guest-Session-Token")
            })
        }.build()

    val retrofit: Retrofit = Retrofit.Builder().baseUrl(baseUrl).client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(Json.moshi)).build()

    val api: BackendApi = retrofit.create(BackendApi::class.java)
}
