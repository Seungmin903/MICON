package com.micon.app.data.holo

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.onboarding.VoiceSessionAccess
import com.micon.app.range.VoiceQualityPoint
import com.micon.app.range.VoiceQualityResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoloAnalysisClientQualityTest {
    @Test fun finalOnboardingSessionReceivesMeasuredQuality() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = BackendClient(server.url("/api/v1/").toString(), false,
                MemoryTokenStore().apply { saveSession(42, "access", "refresh") }).api
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"code":"SUCCESS","message":"ok","data":{"sessionId":7,"status":"RANGE_IN_PROGRESS","rangeStatus":"AVAILABLE","timbreStatus":"PENDING","artistSearchStatus":"PENDING","resultVersion":1,"savedProfileExists":false,"recoverable":true,"expiresAt":"2026-09-26T00:00:00"}}"""))
            val quality = VoiceQualityResult("personal-acoustic-v1", 57,
                listOf(VoiceQualityPoint(57, 100.0, 0.0, 0.0, 100.0)))
            val result = HoloResult(measuredAt = 1L, lowMidi = 50, highMidi = 70,
                comfortLow = 55, comfortHigh = 65, recommendLow = 56, recommendHigh = 64,
                stability = listOf(1f), stabilityMidis = listOf(57), voiceQuality = quality,
                phraseSec = 4.0, bpmLow = 100, bpmHigh = 120, notes = emptyList(), voicedSec = 10.0)
            HoloAnalysisClient(api).submitRange(HoloAnalysisClient.Session(7, VoiceSessionAccess(42)), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/voice-analysis-sessions/7/range-result", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"voiceQuality\""))
            assertTrue(body.contains("\"referenceMidi\":57"))
            assertTrue(body.contains("\"score\":100.0"))
        } finally { server.shutdown() }
    }
}
