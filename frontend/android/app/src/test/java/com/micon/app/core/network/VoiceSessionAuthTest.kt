package com.micon.app.core.network

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.data.onboarding.VoiceSessionAccess
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceSessionAuthTest {
    private val server = MockWebServer()
    private val tokens = MemoryTokenStore()
    private lateinit var client: BackendClient
    private var expirations = 0

    @Before fun setup() {
        server.start()
        tokens.saveSession(7, "member-access", "member-refresh")
        client = BackendClient(server.url("/api/v1/").toString(), false, tokens) { expirations++ }
    }
    @After fun teardown() { server.shutdown() }

    @Test fun guestFailureDoesNotSendOrRefreshMemberCredentials() {
        server.enqueue(MockResponse().setResponseCode(401))
        val request = Request.Builder().url(server.url("/api/v1/voice-analysis-sessions/active"))
            .header("Authorization", "Bearer stale")
            .tag(VoiceSessionAccess::class.java, VoiceSessionAccess(null, "guest-key")).build()
        client.okHttp.newCall(request).execute().close()
        val sent = server.takeRequest()
        assertNull(sent.getHeader("Authorization"))
        assertEquals("guest-key", sent.getHeader("X-Guest-Session-Token"))
        assertEquals(1, server.requestCount)
        assertEquals("member-access", tokens.snapshot().accessToken)
        assertEquals(0, expirations)
    }

    @Test fun wrongMemberIsRejectedBeforeSendingSessionData() {
        val request = Request.Builder().url(server.url("/api/v1/voice-analysis-sessions/12"))
            .tag(VoiceSessionAccess::class.java, VoiceSessionAccess(99)).build()
        assertTrue(runCatching { client.okHttp.newCall(request).execute().close() }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun memberRequestUsesMemberTokenAndRemovesGuestHeader() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder().url(server.url("/api/v1/voice-analysis-sessions/12"))
            .header("X-Guest-Session-Token", "stale-guest")
            .tag(VoiceSessionAccess::class.java, VoiceSessionAccess(7)).build()
        client.okHttp.newCall(request).execute().close()
        val sent = server.takeRequest()
        assertEquals("Bearer member-access", sent.getHeader("Authorization"))
        assertNull(sent.getHeader("X-Guest-Session-Token"))
    }

    @Test fun searchAndAssetsArePublicWhileDetailAcceptsMemberIdentity() {
        for (path in listOf("songs?query=test", "songs/12/practice-assets", "songs/12")) {
            server.enqueue(MockResponse().setResponseCode(200))
            client.okHttp.newCall(Request.Builder().url(server.url("/api/v1/" + path)).build()).execute().close()
            val sent = server.takeRequest()
            if (path == "songs/12") assertEquals("Bearer member-access", sent.getHeader("Authorization"))
            else assertNull(sent.getHeader("Authorization"))
        }
    }
}
