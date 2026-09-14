package com.micon.app.data.settings

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.backend.BackendRequestException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 백엔드 `SettingController`·`VoiceDataDeletionController` 계약을 고정한다. */
class RemoteSettingsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteSettingsRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemoteSettingsRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String) = json(200, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")

    @Test fun `settings reads and patches saveOriginalRecording`() = runBlocking {
        server.enqueue(ok("""{"saveOriginalRecording":false}"""))
        assertFalse(repo.settings().saveOriginalRecording)
        assertEquals("/api/v1/users/me/settings", server.takeRequest().path)

        server.enqueue(ok("""{"saveOriginalRecording":true}"""))
        assertTrue(repo.updateSaveOriginalRecording(true).saveOriginalRecording)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("/api/v1/users/me/settings", request.path)
        assertEquals("""{"saveOriginalRecording":true}""", request.body.readUtf8())
    }

    @Test fun `deleteVoiceData sends confirmed and maps counts`() = runBlocking {
        server.enqueue(ok("""{"deletionId":12,"status":"COMPLETED","deletedAnalysisCount":3,"deletedRecordingCount":5,
            "requestedAt":"2026-09-14T10:00:00","completedAt":"2026-09-14T10:00:01"}"""))
        val result = repo.deleteVoiceData()
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/v1/users/me/voice-data-deletions", request.path)
        assertEquals("""{"confirmed":true}""", request.body.readUtf8())
        assertTrue(result.isCompleted); assertEquals(12L, result.deletionId)
        assertEquals(3, result.deletedAnalysisCount); assertEquals(5, result.deletedRecordingCount)
    }

    @Test fun `in-progress analysis blocks deletion with a backend code`() = runBlocking {
        server.enqueue(json(409, """{"code":"VOICE_ANALYSIS_IN_PROGRESS","message":"해당 녹음의 분석이 이미 진행 중입니다.","data":null}"""))
        try {
            repo.deleteVoiceData()
            fail("expected BackendRequestException")
        } catch (e: BackendRequestException) {
            assertEquals(409, e.status); assertEquals("VOICE_ANALYSIS_IN_PROGRESS", e.code)
        }
    }
}
