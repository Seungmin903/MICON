package com.micon.app.data.practice

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.backend.BackendRequestException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 백엔드 연습 기록 계약(`PracticeController`·`PracticeRequest/Response`·`PracticeResultRequest/Response`·`PracticeHistoryResponse`,
 * `ApiResponse` 봉투)을 고정한다. 응답 JSON 은 backend/src/main/java/com/micon/backend/practice/api 의 record 필드명 그대로다.
 */
class RemotePracticeRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemotePracticeRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemotePracticeRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String) = json(200, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")
    private val detail = """{"practiceId":31,"songId":7,"songTitle":"빈잔","sectionId":null,"recordingId":null,"voiceAnalysisId":null,"selectedKey":-2,
        "playbackSpeed":1.00,"customStartMs":null,"customEndMs":null,"practiceScope":"FULL_SONG","status":"READY","startedAt":null,"endedAt":null,
        "durationMs":null,"createdAt":"2026-09-13T21:00:00"}"""

    @Test fun `practice creation sends the uuid as a string and keeps numeric practice ids`() = runBlocking {
        val id = "d221afea-6dc1-5223-a6bc-68fa055b4820"
        server.enqueue(ok(detail.replace("\"songId\":7", "\"songId\":\"$id\"")))
        val record = repo.create(id, -2)
        assertEquals(id, record.songId)
        assertEquals(31L, record.id)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"songId\":\"$id\""))
    }

    @Test fun `create sends FULL_SONG request and parses detail`() = runBlocking {
        server.enqueue(ok(detail))
        val record = repo.create(songId = "7", selectedKey = -2)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/practices/session", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"songId\":\"7\""))
        assertTrue(sent.contains("\"selectedKey\":-2"))
        assertTrue(sent.contains("\"practiceScope\":\"FULL_SONG\""))
        assertFalse(sent.contains("recordingId"))              // null 은 보내지 않는다(서버 @Min 검증 회피)
        assertEquals(31L, record.id)
        assertEquals("READY", record.status)
        assertEquals(-2, record.selectedKey)
        assertFalse(record.isCompleted)
    }

    @Test fun `complete and cancel hit the lifecycle endpoints`() = runBlocking {
        server.enqueue(ok(detail.replace("\"READY\"", "\"COMPLETED\"").replace("\"durationMs\":null", "\"durationMs\":183000")))
        val completed = repo.complete(31, 183_000)
        var request = server.takeRequest()
        assertEquals("/api/v1/practices/31/complete", request.path)
        assertTrue(request.body.readUtf8().contains("\"durationMs\":183000"))
        assertTrue(completed.isCompleted); assertEquals(183_000, completed.durationMs)

        server.enqueue(ok(detail.replace("\"READY\"", "\"ABORTED\"")))
        assertEquals("ABORTED", repo.cancel(31).status)
        assertEquals("/api/v1/practices/31/cancel", server.takeRequest().path)
    }

    @Test fun `complete sends the sung key and reads V29 key fields`() = runBlocking {
        // BE fix/be/practiceSessionKey: 세션은 원키로 열고(initialKey 0) 완료 때 finalSelectedKey·keyChangeCount 로 실제 키를 남긴다
        server.enqueue(ok(detail.replace("\"READY\"", "\"COMPLETED\"").replace("\"selectedKey\":-2", "\"initialKey\":0,\"selectedKey\":-2,\"keyChangeCount\":1,\"keyChanged\":true")))
        val completed = repo.complete(31, 183_000, finalSelectedKey = -2, keyChangeCount = 1)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"finalSelectedKey\":-2") && body.contains("\"keyChangeCount\":1"))
        assertEquals(0, completed.initialKey); assertEquals(-2, completed.selectedKey); assertTrue(completed.keyChanged)

        // 키 필드를 주지 않으면 본문에도 없다(서버는 없는 필드를 최초 키 유지로 본다)
        server.enqueue(ok(detail))
        val plain = repo.complete(31, 1000)
        val plainBody = server.takeRequest().body.readUtf8()
        assertFalse(plainBody, plainBody.contains("finalSelectedKey") || plainBody.contains("keyChangeCount"))
        assertEquals(-2, plain.initialKey); assertFalse(plain.keyChanged)   // 응답에 없으면 최종 키 = 최초 키
    }

    @Test fun `savePerformance rounds scores to two decimals and parses report`() = runBlocking {
        server.enqueue(ok("""{"practiceId":31,"songId":7,"songTitle":"빈잔","selectedKey":-2,"practiceScope":"FULL_SONG","sectionId":null,"durationMs":183000,
            "status":"COMPLETED","overallScore":81.23,"pitchScore":85.00,"sustainScore":null,"rhythmScore":74.20,"summaryText":"잘 불렀어요.\n정확 12 · 높게 3",
            "sections":[{"sectionId":501,"score":88.00,"measured":true,"summary":"1절 · 정확 8"},{"sectionId":502,"score":null,"measured":false,"summary":"2절 · 부르지 않음"}],
            "weakSection":{"sectionId":502,"selectedKey":-2,"score":null},"analyzedAt":"2026-09-13T21:05:00"}"""))
        val report = repo.savePerformance(
            31,
            PracticeResultInput(
                overallScore = 81.2345, pitchScore = 84.999, rhythmScore = 74.2, sustainScore = null,
                summaryText = "잘 불렀어요.\n정확 12 · 높게 3",
                sections = listOf(SectionEvaluation(501, 88.004, true, "1절 · 정확 8"), SectionEvaluation(502, null, false, "2절 · 부르지 않음")),
            ),
        )
        val request = server.takeRequest()
        assertEquals("PUT", request.method); assertEquals("/api/v1/practices/report/31", request.path)
        val sent = request.body.readUtf8()
        assertTrue(sent.contains("\"status\":\"COMPLETED\""))
        assertTrue(sent.contains("\"overallScore\":81.23"))
        assertTrue(sent.contains("\"pitchScore\":85.0"))
        assertTrue(sent.contains("\"rhythmScore\":74.2"))
        assertFalse(sent.contains("sustainScore"))          // null 필드는 생략된다(Moshi 기본). 서버 @DecimalMin 은 null 을 허용
        assertTrue(sent.contains("\"sectionId\":501") && sent.contains("\"score\":88.0") && sent.contains("\"measured\":true"))
        assertTrue(sent.contains("\"sectionId\":502") && sent.contains("\"measured\":false"))
        assertEquals(81.23, report.overallScore!!, 1e-9)
        assertEquals(2, report.sections.size)
        assertFalse(report.sections[1].measured)
        assertEquals(502L, report.weakSection?.sectionId)
        assertEquals("잘 불렀어요.", report.summaryText!!.lines().first())
    }

    @Test fun `history maps page summaries`() = runBlocking {
        server.enqueue(ok("""{"content":[{"practiceId":31,"songId":7,"songTitle":"빈잔","imageUrl":"https://img/7.jpg","selectedKey":-2,"practiceScope":"FULL_SONG",
            "sectionId":null,"status":"COMPLETED","startedAt":"2026-09-13T21:00:00","durationMs":183000,"resultStatus":"COMPLETED","overallScore":81.23},
            {"practiceId":30,"songId":1,"songTitle":"너의 번호를 누르고","imageUrl":null,"selectedKey":0,"practiceScope":"FULL_SONG","sectionId":null,
            "status":"ABORTED","startedAt":"2026-09-12T10:00:00","durationMs":null,"resultStatus":null,"overallScore":null}],
            "page":0,"size":50,"totalElements":2,"totalPages":1}"""))
        val page = repo.history(page = 0, size = 50)
        assertEquals("/api/v1/practices/report?page=0&size=50", server.takeRequest().path)
        assertEquals(2L, page.totalElements)
        assertEquals(31L, page.items[0].id); assertEquals(81.23, page.items[0].overallScore!!, 1e-9)
        assertEquals("ABORTED", page.items[1].status); assertNull(page.items[1].overallScore)
    }

    @Test fun `report returns null when no result and rethrows other errors`() = runBlocking {
        server.enqueue(json(404, """{"code":"PRACTICE_RESULT_NOT_FOUND","message":"연습 결과를 찾을 수 없습니다.","data":null}"""))
        assertNull(repo.report(31))
        assertEquals("/api/v1/practices/report/31", server.takeRequest().path)
        server.enqueue(json(404, """{"code":"PRACTICE_NOT_FOUND","message":"연습 기록을 찾을 수 없습니다.","data":null}"""))
        try {
            repo.report(99)
            fail("expected BackendRequestException")
        } catch (e: BackendRequestException) {
            assertEquals(404, e.status); assertEquals("PRACTICE_NOT_FOUND", e.code)
        }
    }

    @Test fun `conflict surfaces backend code for the caller to react`() = runBlocking {
        server.enqueue(json(409, """{"code":"INVALID_PRACTICE_STATE","message":"현재 상태에서는 요청한 연습 작업을 수행할 수 없습니다.","data":null}"""))
        try {
            repo.complete(31, 1000)
            fail("expected BackendRequestException")
        } catch (e: BackendRequestException) {
            assertEquals(409, e.status); assertEquals("INVALID_PRACTICE_STATE", e.code)
        }
    }

    @Test fun `score2 clamps and rounds`() {
        assertEquals(100.0, RemotePracticeRepository.score2(123.4), 1e-9)
        assertEquals(0.0, RemotePracticeRepository.score2(-3.0), 1e-9)
        assertEquals(66.67, RemotePracticeRepository.score2(66.666), 1e-9)
    }
}
