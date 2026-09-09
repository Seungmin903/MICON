package com.micon.app.core.network

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.data.backend.AuthRequestException
import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.data.preferences.PreferenceSessionExpiredException
import kotlinx.coroutines.runBlocking
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BackendAuthContractTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: MemoryTokenStore
    private lateinit var client: BackendClient
    private var expirations = 0

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = MemoryTokenStore()
        client = BackendClient(server.url("/api/v1/").toString(), false, tokens) { expirations++ }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `email login sends public POST and persists response identity with token pair`() = runBlocking {
        tokens.saveSession(7, "previous-access", "previous-refresh")
        server.enqueue(json(200, """{"userId":42,"email":"user@example.com","accessToken":"login-access","refreshToken":"login-refresh","accessTokenExpiresAt":"2026-09-09T10:00:00Z"}"""))
        NetworkAuthRepository(client.api, tokens).login(" user@example.com ", "test-password")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/sessions", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"email\":\"user@example.com\""))
        assertTrue(body.contains("\"password\":\"test-password\""))
        assertEquals(42L, tokens.snapshot().userId)
        assertEquals("login-access", tokens.snapshot().accessToken)
        assertEquals("login-refresh", tokens.snapshot().refreshToken)
    }

    @Test fun `signup duplicate email is retryable inline failure without authenticating`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"EMAIL_ALREADY_EXISTS","message":"duplicate","data":null}"""))
        val error = runCatching { NetworkAuthRepository(client.api, tokens).signup("user@example.com", "test-password") }.exceptionOrNull()
        assertTrue(error is AuthRequestException)
        assertEquals(409, (error as AuthRequestException).status)
        assertFalse(tokens.snapshot().isLoggedIn)
        assertEquals("/api/v1/users", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        assertEquals(0, expirations)
    }

    @Test fun `invalid credentials never trigger token refresh`() = runBlocking {
        tokens.saveSession(7, "previous-access", "previous-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { NetworkAuthRepository(client.api, tokens).login("bad@example.com", "wrong") }.exceptionOrNull()
        assertEquals(401, (error as AuthRequestException).status)
        assertEquals(1, server.requestCount)
        assertEquals("previous-refresh", tokens.snapshot().refreshToken)
        assertEquals(0, expirations)
    }

    @Test fun `malformed success cannot create an authenticated session`() = runBlocking {
        server.enqueue(json(200, """{"userId":42,"email":"user@example.com","accessToken":"","refreshToken":"r"}"""))
        assertTrue(runCatching { NetworkAuthRepository(client.api, tokens).login("user@example.com", "password") }.isFailure)
        assertFalse(tokens.snapshot().isLoggedIn)
    }

    @Test fun `inactive account identity response exits session gate instead of retrying forever`() = runBlocking {
        tokens.saveSession(42, "access", "refresh")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"ACCOUNT_NOT_ACTIVE","message":"inactive account","data":null}"""))
        val error = runCatching { NetworkAuthRepository(client.api, tokens).restoreIdentity() }.exceptionOrNull()
        assertTrue(error is AuthSessionExpiredException)
        assertEquals("/api/v1/users/me", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `inactive account preference response routes through existing session expiration handler`() = runBlocking {
        tokens.saveSession(42, "access", "refresh")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"ACCOUNT_NOT_ACTIVE","message":"inactive account","data":null}"""))
        val preferences = NetworkPreferenceRepository(client.retrofit.create(PreferenceApi::class.java))
        val error = runCatching { preferences.getPreferences() }.exceptionOrNull()
        assertTrue(error is PreferenceSessionExpiredException)
        assertEquals("/api/v1/users/me/preferences", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `expired access uses bearer refresh endpoint then retries with rotated access`() = runBlocking {
        tokens.saveSession(42, "old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json(200, """{"accessToken":"new-access","refreshToken":"new-refresh"}"""))
        server.enqueue(json(200, """{"userId":42,"email":"user@example.com"}"""))
        NetworkAuthRepository(client.api, tokens).restoreIdentity()
        val initial = server.takeRequest()
        val refresh = server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("Bearer old-access", initial.getHeader("Authorization"))
        assertEquals("/api/v1/auth/tokens/refresh", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals("Bearer old-refresh", refresh.getHeader("Authorization"))
        assertEquals(0L, refresh.bodySize)
        assertEquals("Bearer new-access", retried.getHeader("Authorization"))
        assertEquals("new-refresh", tokens.snapshot().refreshToken)
        assertEquals(0, expirations)
    }

    @Test fun `rejected refresh clears session and emits expiration once without recursion`() = runBlocking {
        tokens.saveSession(42, "old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, client.api.currentUser().code())
        assertFalse(tokens.snapshot().isLoggedIn)
        assertEquals(2, server.requestCount)
        assertEquals(1, expirations)
    }

    @Test fun `temporary refresh server failure preserves session for retry`() = runBlocking {
        tokens.saveSession(42, "old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { client.api.currentUser() }.exceptionOrNull() is IOException)
        assertEquals("old-refresh", tokens.snapshot().refreshToken)
        assertEquals(0, expirations)
    }

    @Test fun `second unauthorized response after successful refresh clears protected session`() = runBlocking {
        tokens.saveSession(42, "old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json(200, """{"accessToken":"new-access","refreshToken":"new-refresh"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, client.api.currentUser().code())
        assertEquals(3, server.requestCount)
        assertFalse(tokens.snapshot().isLoggedIn)
        assertEquals(1, expirations)
    }

    @Test fun `logout during refresh cannot resurrect tokens`() = runBlocking {
        tokens.saveSession(42, "old-access", "old-refresh")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.endsWith("/auth/tokens/refresh")) {
                tokens.clear()
                json(200, """{"accessToken":"late-access","refreshToken":"late-refresh"}""")
            } else MockResponse().setResponseCode(401)
        }
        assertEquals(401, client.api.currentUser().code())
        assertFalse(tokens.snapshot().isLoggedIn)
        assertEquals(2, server.requestCount)
    }

    @Test fun `logout uses authenticated DELETE and clears locally despite server failure`() = runBlocking {
        tokens.saveSession(42, "access", "refresh")
        server.enqueue(MockResponse().setResponseCode(500))
        NetworkAuthRepository(client.api, tokens).logout()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/auth/sessions/current", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertFalse(tokens.snapshot().isLoggedIn)
    }

    @Test fun `debug HTTP logging never logs passwords or token response bodies`() {
        val debugClient = BackendClient(server.url("/").toString(), true, tokens)
        val logger = debugClient.okHttp.interceptors.filterIsInstance<HttpLoggingInterceptor>().single()
        assertEquals(HttpLoggingInterceptor.Level.BASIC, logger.level)
    }

    private fun json(status: Int, data: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody("""{"code":"SUCCESS","message":"success","data":$data}""")
}
