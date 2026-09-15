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
    @Test fun `latest saved profile is restored with server range and result version`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        try {
            server.enqueue(ok(200, """{"profileId":3,"sessionId":7,"profileVersion":4}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"SAVED","resultVersion":9,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","saveAllowed":false,"savedProfileExists":true,"expiresAt":"2026-09-15T00:00:00","range":{"lowMidi":48.0,"highMidi":72.0,"measurementStatus":"AVAILABLE","qualityStatus":"VALID","noteResults":[{"midi":48,"status":"passed"}]},"components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))

            val saved = gateway.latestSaved(42)
            assertNotNull(saved)
            checkNotNull(saved)
            assertEquals("3", saved.receipt.profileId)
            assertEquals(9, saved.receipt.version)
            assertEquals(48, saved.range.lowMidi)
            assertEquals(72, saved.range.highMidi)
            assertEquals(7L, saved.range.backendSessionId)
            assertEquals(VoiceSessionStatus.SAVED, saved.progress.sessionStatus)
            assertEquals(listOf(
                "/api/v1/users/me/voice-profile",
                "/api/v1/voice-analysis-sessions/7/result"),
                (1..2).map { server.takeRequest().path })
        } finally { server.shutdown() }
    }

    @Test fun `completed artist search with no matches is not left pending`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        try {
            server.enqueue(ok(200, """{"sessionId":7,"status":"PARTIAL_READY","rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"NO_RESULT","resultVersion":3,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-15T00:00:00"}"""))
            server.enqueue(ok(200, """{"analysisId":9,"sessionId":7,"status":"COMPLETED","components":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"NO_RESULT","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2026-09-15T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))

            val progress = checkNotNull(gateway.recover(42))

            assertEquals(ComponentStatus.COMPLETED, progress.artistSearch)
        } finally { server.shutdown() }
    }

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
