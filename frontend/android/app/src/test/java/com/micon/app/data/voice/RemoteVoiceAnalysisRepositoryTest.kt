package com.micon.app.data.voice

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 백엔드 음성 계약(`RecordingController`·`VoiceAnalysisController`, `ApiResponse` 봉투)을 고정한다.
 * 응답 JSON 은 backend/src/main/java/com/micon/backend/voice/api 의 record 필드명 그대로다.
 */
class RemoteVoiceAnalysisRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteVoiceAnalysisRepository
    private lateinit var wav: File

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemoteVoiceAnalysisRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
        wav = File.createTempFile("sing-test", ".wav").apply { writeBytes(ByteArray(44 + 3_000) { (it % 7).toByte() }) }
    }

    @After fun tearDown() { server.shutdown(); wav.delete() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String, code: Int = 200) = json(code, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")

    @Test fun `upload posts multipart wav with attemptNo and reports progress`() = runBlocking {
        server.enqueue(ok("""{"recordingId":57}""", 201))
        val progress = ArrayList<Float>()
        val id = repo.upload(wav) { progress += it }

        assertEquals(57L, id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/recordings", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("""name="file"; filename="${wav.name}""""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("""name="attemptNo""""))
        assertTrue(body.contains("\r\n\r\n1\r\n"))
        assertTrue(body.contains("keepOriginal").not())            // 생략 → 서버 기본 정책
        assertTrue(progress.isNotEmpty() && progress.last() == 1f)
    }

    @Test fun `create sends SONG request and parses status`() = runBlocking {
        server.enqueue(ok("""{"analysisId":9,"recordingId":57,"songId":42,"method":"SONG","status":"ANALYZING","qualityStatus":null,
            "failureCode":null,"failureReason":null,"analysisVersion":"v1","startedAt":"2026-09-13T21:00:00","completedAt":null,"createdAt":"2026-09-13T20:59:58"}""", 201))
        val analysis = repo.create(57, 42)
        val request = server.takeRequest()
        assertEquals("/api/v1/voice-analyses", request.path)
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"recordingId\":57"))
        assertTrue(sent.contains("\"method\":\"SONG\""))
        assertTrue(sent.contains("\"songId\":42"))
        assertEquals(9L, analysis.id)
        assertEquals(VoiceAnalysisStatus.ANALYZING, analysis.status)
        assertEquals(42L, analysis.songId)
    }

    @Test fun `create failure surfaces backend error code`() = runBlocking {
        server.enqueue(json(503, """{"code":"VOICE_ANALYSIS_DISPATCH_FAILED","message":"음성 분석 작업을 요청하지 못했습니다.","data":null}"""))
        try {
            repo.create(57, 42)
            fail("expected VoiceApiException")
        } catch (e: VoiceApiException) {
            assertEquals(503, e.status)
            assertEquals("VOICE_ANALYSIS_DISPATCH_FAILED", e.code)
            assertEquals("음성 분석 작업을 요청하지 못했습니다.", e.message)
        }
    }

    @Test fun `unknown status does not break parsing`() = runBlocking {
        server.enqueue(ok("""{"analysisId":9,"status":"SOMETHING_NEW"}"""))
        assertEquals(VoiceAnalysisStatus.UNKNOWN, repo.get(9).status)
        assertEquals("/api/v1/voice-analyses/9", server.takeRequest().path)
    }

    @Test fun `latest is null when server has no analysis`() = runBlocking {
        server.enqueue(ok("null"))
        assertNull(repo.latest())
        assertEquals("/api/v1/voice-analyses/latest", server.takeRequest().path)
        server.enqueue(ok("""{"analysisId":3,"recordingId":1,"songId":null,"method":"SCALE","status":"COMPLETED","completedAt":"2026-09-13T21:05:00","createdAt":"2026-09-13T21:00:00"}"""))
        val latest = checkNotNull(repo.latest())
        assertEquals(3L, latest.id)
        assertEquals(VoiceAnalysisStatus.COMPLETED, latest.status)
        assertNull(latest.songId)
    }

    @Test fun `report maps range and timbre and tolerates json nodes`() = runBlocking {
        server.enqueue(ok("""{
            "analysis":{"analysisId":9,"recordingId":57,"songId":42,"method":"SONG","status":"COMPLETED"},
            "range":{"fullLowMidi":48.0,"fullHighMidi":71.5,"stableLowMidi":53.0,"stableHighMidi":65.0,"pitchMedianMidi":59.2,
                     "pitchAccuracyScore":0.81,"pitchStabilityScore":0.77,"pitchDistribution":{"48":0.1,"60":0.9},"modalLowMidi":null,
                     "modalHighMidi":null,"falsettoLowMidi":null,"falsettoHighMidi":null,"highNoteHoldSec":1.5,"confidence":0.9,
                     "noteResults":[{"midi":60,"ok":true}],"measuredAt":"2026-09-13T21:05:00"},
            "timbre":{"timbreLabel":"따뜻한 중저음","rhythmScore":0.7,"pronunciationScore":0.6,"confidence":0.8,"explanation":"…",
                      "validSingleSec":42.0,"validChunkCount":10,"chunkCohesion":0.5,"observedCoreLowMidi":52.0,"observedCoreHighMidi":64.0,
                      "observedMedianMidi":58.0,"pitchHistogram":[1,2,3],"measuredAt":"2026-09-13T21:05:00"}
        }"""))
        val report = repo.report(9)
        assertEquals("/api/v1/voice-analyses/9/report", server.takeRequest().path)
        assertEquals(VoiceAnalysisStatus.COMPLETED, report.analysis.status)
        val range = checkNotNull(report.range)
        assertEquals(48.0, range.fullLowMidi!!, 1e-9)
        assertEquals(71.5, range.fullHighMidi!!, 1e-9)
        assertEquals(53.0, range.stableLowMidi!!, 1e-9)
        assertEquals("2026-09-13T21:05:00", range.measuredAt)
        val timbre = checkNotNull(report.timbre)
        assertEquals("따뜻한 중저음", timbre.label)
        assertEquals(52.0, timbre.coreLowMidi!!, 1e-9)
    }

    @Test fun `report without range rows keeps analysis`() = runBlocking {
        server.enqueue(ok("""{"analysis":{"analysisId":9,"status":"ANALYZING"},"range":null,"timbre":null}"""))
        val report = repo.report(9)
        assertNotNull(report.analysis)
        assertNull(report.range); assertNull(report.timbre)
    }
}
