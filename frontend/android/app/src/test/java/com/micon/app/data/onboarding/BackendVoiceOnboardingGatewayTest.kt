package com.micon.app.data.onboarding

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.singanalysis.*
import com.micon.app.range.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackendVoiceOnboardingGatewayTest {
    @Test fun `member submits range and song then saves ready profile`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        val wav = File.createTempFile("voice-onboarding", ".wav").apply {
            writeBytes(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                0, 0, 0, 0, 'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 1))
        }
        try {
            server.enqueue(ok(201, """{"sessionId":7,"status":"CREATED","rangeStatus":"PENDING","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":0,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-15T00:00:00"}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"RANGE_IN_PROGRESS","rangeStatus":"AVAILABLE","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":1,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-15T00:00:00"}"""))
            server.enqueue(ok(202, """{"analysisId":9,"sessionId":7,"status":"QUEUED","components":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":2,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2026-09-15T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))
            server.enqueue(ok(201, """{"profileId":3,"sessionId":7,"profileVersion":1}"""))

            val recording = SavedRecording(SingRecordingMeta("recording", songId = 1, title = "Song", artist = "Artist",
                scope = "FULL", planSource = "SONG_START", plannedStartSec = 0.0, plannedEndSec = 35.0,
                sungStartSec = 0.0, sungEndSec = 35.0, endedEarly = false, recordedAt = 0,
                sampleRate = 48000, durationSec = 35.0, takes = listOf(SingTake(0.0, 35.0, 0.0, 35.0)),
                lyricTiming = "SYNCED", wavFileName = wav.name, onboardingDraftId = "draft"), wav, wav)
            val range = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(48, "passed")), 48, 72,
                emptyList(), "completed", "FOUND", "FOUND", "available", verification = "device_measured")
            val request = VoiceOnboardingRequest("draft", 1, 42, range, recording)

            val result = gateway.analyze(request) {}
            assertTrue(result.canSave)
            val receipt = gateway.save(request, result)
            assertEquals("7", receipt.sessionId)
            assertEquals(2, receipt.version)
            assertEquals(listOf(
                "/api/v1/voice-analysis-sessions",
                "/api/v1/voice-analysis-sessions/7/range-result",
                "/api/v1/voice-analysis-sessions/7/analysis",
                "/api/v1/voice-analysis-sessions/7/result",
                "/api/v1/voice-analysis-sessions/7/profile"),
                (1..5).map { server.takeRequest().path })
        } finally { wav.delete(); server.shutdown() }
    }

    private fun ok(status: Int, data: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":"SUCCESS","message":"ok","data":$data}""")
}
