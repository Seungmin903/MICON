package com.micon.app.data.onboarding

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.singanalysis.*
import com.micon.app.range.*
import com.micon.app.ui.report.hasTimbreReport
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
            assertTrue(saved.progress.hasTimbreReport(saved.range))
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
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"NO_RESULT","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2026-09-15T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))
            server.enqueue(ok(200, """{"analysisId":9,"sessionId":7,"status":"COMPLETED","components":[]}"""))

            val progress = checkNotNull(gateway.recover(42, 7))

            assertEquals(ComponentStatus.COMPLETED, progress.artistSearch)
            assertEquals(listOf("/api/v1/voice-analysis-sessions/7/result", "/api/v1/voice-analysis-sessions/7/analysis"),
                (1..2).map { server.takeRequest().path })
        } finally { server.shutdown() }
    }

    @Test fun `resumed running analysis polls the pinned session without uploading again`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        try {
            server.enqueue(ok(200, """{"sessionId":7,"status":"ANALYZING","resultVersion":2,"rangeStatus":"AVAILABLE","timbreStatus":"ANALYZING","saveAllowed":false,"savedProfileExists":false,"expiresAt":"2099-09-16T00:00:00","components":[{"component":"ECAPA","status":"RUNNING"},{"component":"SINGER_BYOL","status":"RUNNING"}],"artists":[]}"""))
            server.enqueue(ok(200, """{"analysisId":9,"sessionId":7,"status":"RUNNING","components":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"NO_RESULT","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2099-09-16T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))
            val updates = mutableListOf<VoiceAnalysisProgress>()
            val result = gateway.recover(42, 7, updates::add)
            assertTrue(result!!.canSave)
            assertEquals(listOf(VoiceSessionStatus.ANALYZING, VoiceSessionStatus.READY_TO_SAVE), updates.map { it.sessionStatus })
            assertEquals(9L, result.analysisId)
            val requests = (1..3).map { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(listOf("/api/v1/voice-analysis-sessions/7/result", "/api/v1/voice-analysis-sessions/7/analysis",
                "/api/v1/voice-analysis-sessions/7/result"), requests.map { it.path })
        } finally { server.shutdown() }
    }

    @Test fun `missing pinned session never falls back to another active session`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(gateway.recover(42, 7))
            assertEquals(1, server.requestCount)
            assertEquals("/api/v1/voice-analysis-sessions/7/result", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun `artist lookup continues from ready to saved without a result version change`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        val waiting = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
            byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.PENDING,
            sessionStatus = VoiceSessionStatus.READY_TO_SAVE, rangeStatus = "AVAILABLE", saveAllowed = true)
        try {
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"RUNNING","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2099-09-16T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"},{"component":"ARTIST_SEARCH","status":"QUEUED"}],"artists":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"SAVED","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"COMPLETED","saveAllowed":false,"savedProfileExists":true,"expiresAt":"2099-09-16T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"},{"component":"ARTIST_SEARCH","status":"COMPLETED"}],"artists":[{"artistId":5,"name":"Singer","imageUrl":null,"similarity":0.8}]}"""))
            val updates = mutableListOf<VoiceAnalysisProgress>()
            val result = gateway.awaitArtists(42, waiting, updates::add)
            assertEquals(listOf(ComponentStatus.QUEUED, ComponentStatus.COMPLETED), updates.map { it.artistSearch })
            assertTrue(updates.first().canSave)
            assertEquals(VoiceSessionStatus.SAVED, result.sessionStatus)
            assertEquals(3L, result.version)
            assertEquals(listOf(5L), result.visibleArtists.map { it.artistId })
            assertEquals(listOf("/api/v1/voice-analysis-sessions/7/result", "/api/v1/voice-analysis-sessions/7/result"),
                (1..2).map { server.takeRequest().path })
        } finally { server.shutdown() }
    }

    @Test fun `artist lookup stops on no result or backend failure without a component job`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        val waiting = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
            byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.PENDING,
            sessionStatus = VoiceSessionStatus.SAVED, rangeStatus = "AVAILABLE")
        try {
            for ((backendStatus, expected) in listOf("NO_RESULT" to ComponentStatus.COMPLETED, "FAILED" to ComponentStatus.FAILED)) {
                server.enqueue(ok(200, """{"sessionId":7,"status":"SAVED","resultVersion":3,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","artistSearchStatus":"$backendStatus","saveAllowed":false,"savedProfileExists":true,"expiresAt":"2099-09-16T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))
                val result = gateway.awaitArtists(42, waiting) {}
                assertEquals(expected, result.artistSearch)
                assertFalse(result.awaitingArtistResult)
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `member submits range and song then saves ready profile`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        val wav = File.createTempFile("voice-onboarding", ".wav")
        try {
            server.enqueue(ok(201, """{"sessionId":7,"status":"CREATED","rangeStatus":"PENDING","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":0,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-15T00:00:00"}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"RANGE_IN_PROGRESS","rangeStatus":"AVAILABLE","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":1,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-15T00:00:00"}"""))
            server.enqueue(ok(202, """{"analysisId":9,"sessionId":7,"status":"QUEUED","components":[]}"""))
            server.enqueue(ok(200, """{"sessionId":7,"status":"READY_TO_SAVE","resultVersion":2,"rangeStatus":"AVAILABLE","timbreStatus":"AVAILABLE","saveAllowed":true,"savedProfileExists":false,"expiresAt":"2026-09-15T00:00:00","components":[{"component":"ECAPA","status":"COMPLETED"},{"component":"SINGER_BYOL","status":"COMPLETED"}],"artists":[]}"""))
            server.enqueue(ok(201, """{"profileId":3,"sessionId":7,"profileVersion":1}"""))

            val recording = onboardingRecording(wav, "draft")
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

    @Test fun `invalid input creates neither a session nor a model job`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(
            BackendClient(server.url("/api/v1/").toString(), false, tokens).api, TestSubmissionStateStore())
        val wav = File.createTempFile("voice-onboarding", ".wav")
        try {
            val recording = onboardingRecording(wav, "draft", seconds = 121.0)
            val range = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72,
                emptyList(), "completed", "FOUND", "FOUND", "available")
            try {
                gateway.analyze(VoiceOnboardingRequest("draft", 1, 42, range, recording)) {}
                fail("Invalid audio must be rejected before any request")
            } catch (expected: VoiceOnboardingNotReady) {
                assertTrue(expected.message!!.contains("2분"))
            }
            assertEquals(0, server.requestCount)
        } finally { wav.delete(); server.shutdown() }
    }

    @Test fun `silent recording and model retries are rejected before any request`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(42, "access", "refresh") }
        val gateway = BackendVoiceOnboardingGateway(BackendClient(server.url("/api/v1/").toString(), false, tokens).api)
        val wav = File.createTempFile("silent-onboarding", ".wav")
        try {
            val recording = onboardingRecording(wav, "draft")
            com.micon.app.audio.writeQualityWav(wav, recording.meta.sampleRate, recording.meta.durationSec, amplitude = 0.0)
            val range = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(), "completed", "FOUND", "FOUND", "available")
            val request = VoiceOnboardingRequest("draft", 1, 42, range, recording)
            for (retry in listOf(null, AnalysisComponent.ECAPA, AnalysisComponent.SINGER_BYOL)) {
                val failure = runCatching { gateway.analyze(request, retry) {} }.exceptionOrNull()
                assertTrue(failure is VoiceOnboardingNotReady)
            }
            assertEquals(0, server.requestCount)
        } finally { wav.delete(); server.shutdown() }
    }

    private fun ok(status: Int, data: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":"SUCCESS","message":"ok","data":$data}""")
}
