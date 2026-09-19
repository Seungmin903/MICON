package com.micon.app.data.onboarding

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.core.network.Json
import com.micon.app.range.*
import com.micon.app.audio.writeQualityWav
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackendRangeAnalysisGatewayTest {
    @Test fun lostAcceptanceReusesPersistedRequestAfterGatewayRecreation() = runBlocking {
        val server = MockWebServer().apply { start() }
        val wav = File.createTempFile("range-contract", ".wav").apply { writeQualityWav(this) }
        try {
            val api = BackendClient(server.url("/api/v1/").toString(), false,
                MemoryTokenStore().apply { saveSession(42, "access", "refresh") }).api
            val store = TestSubmissionStateStore()
            val state = File(wav.parentFile, wav.name + ".link")
            val submission = usableRangeSubmission().copy(registerEvents = listOf(RegisterEvent(1.0, "modal")))
            server.enqueue(ok(201, """{"sessionId":7,"status":"CREATED","rangeStatus":"PENDING","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":0,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-16T00:00:00"}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { BackendRangeAnalysisGateway(api, 42, state, store).analyze(wav, submission) }.isFailure)
            assertTrue(wav.exists())
            server.enqueue(ok(202, """{"analysisId":9,"sessionId":7,"status":"QUEUED","components":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"RANGE_IN_PROGRESS","resultVersion":1,"rangeStatus":"AVAILABLE","timbreStatus":"PENDING","saveAllowed":false,"savedProfileExists":false,"expiresAt":"2026-09-16T00:00:00","components":[],"artists":[],"range":{"lowMidi":48,"highMidi":72,"measurementStatus":"AVAILABLE","qualityStatus":"VALID","noteResults":[{"midi":48,"status":"passed"}]}}"""))
            val report = BackendRangeAnalysisGateway(api, 42, state, store).analyze(wav, submission)
            assertEquals(7L, report.backendSessionId)
            assertEquals("model_verified", report.verification)
            assertEquals(48, report.notes.single().midi)
            server.takeRequest()
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/api/v1/voice-analysis-sessions/7/range-analysis", second.path)
            fun id(body: String) = body.substringAfter("\"requestId\":\"").substringBefore('"')
            val firstBody = first.body.readUtf8()
            val secondBody = second.body.readUtf8()
            assertEquals(id(firstBody), id(secondBody))
            assertTrue(secondBody.contains("\"strategy\":\"adaptive_6\""))
            assertTrue(secondBody.contains("\"register\":\"modal\""))
            assertEquals("/api/v1/voice-analysis-sessions/7/result", server.takeRequest().path)
        } finally { wav.delete(); server.shutdown() }
    }
    @Test fun invalidInputDoesNotCreateAServerSessionOrSubmitAnyRequest() = runBlocking {
        val server = MockWebServer().apply { start() }
        val wav = File.createTempFile("invalid-range", ".wav").apply { writeQualityWav(this, amplitude = 0.0) }
        try {
            val api = BackendClient(server.url("/api/v1/").toString(), false, MemoryTokenStore()).api
            assertTrue(runCatching { BackendRangeAnalysisGateway(api, 42, File(wav.path + ".link"), TestSubmissionStateStore())
                .analyze(wav, usableRangeSubmission()) }.isFailure)
            assertEquals(0, server.requestCount)
        } finally { wav.delete(); server.shutdown() }
    }
    private fun ok(status: Int, data: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody("""{"code":"SUCCESS","message":"ok","data":$data}""")
}
