package com.micon.app.data.favorites

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.backend.BackendRequestException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 백엔드 `SetlistController` 계약(`SetlistRequest/Response`)을 고정한다. */
class RemoteSetlistRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteSetlistRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemoteSetlistRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String, code: Int = 200) = json(code, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")
    private val summary = """{"setlistId":5,"name":"회식용","songCount":2,"createdAt":"2026-09-13T21:00:00","updatedAt":"2026-09-13T21:30:00"}"""
    private val detail = """{"setlistId":5,"name":"회식용","songs":[
        {"songId":7,"title":"빈잔","artists":["남진"],"imageUrl":null,"durationMs":200000,"position":1},
        {"songId":1,"title":"너의 번호를 누르고","artists":["#안녕","영화처럼"],"imageUrl":null,"durationMs":251158,"position":0}],
        "createdAt":"2026-09-13T21:00:00","updatedAt":"2026-09-13T21:30:00"}"""

    @Test fun `list and detail map summaries and sort songs by position`() = runBlocking {
        server.enqueue(ok("[$summary]"))
        val lists = repo.list()
        assertEquals("/api/v1/setlists", server.takeRequest().path)
        assertEquals(1, lists.size); assertEquals(5L, lists[0].id); assertEquals(2, lists[0].songCount)

        server.enqueue(ok(detail))
        val d = repo.detail(5)
        assertEquals("/api/v1/setlists/5", server.takeRequest().path)
        assertEquals(listOf(1L, 7L), d.songs.map { it.songId })           // position 0, 1 순
        assertEquals("#안녕, 영화처럼", d.songs[0].artist)
    }

    @Test fun `create and rename trim the name and send it as JSON`() = runBlocking {
        server.enqueue(ok(summary, 201))
        repo.create("  회식용  ")
        var request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/v1/setlists", request.path)
        assertEquals("""{"name":"회식용"}""", request.body.readUtf8())

        server.enqueue(ok(summary))
        repo.rename(5, "회식용 2")
        request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("/api/v1/setlists/5", request.path)
        assertEquals("""{"name":"회식용 2"}""", request.body.readUtf8())
    }

    @Test fun `delete add and remove hit the song endpoints`() = runBlocking {
        server.enqueue(ok("null"))
        repo.delete(5)
        var request = server.takeRequest()
        assertEquals("DELETE", request.method); assertEquals("/api/v1/setlists/5", request.path)

        server.enqueue(ok(detail))
        repo.addSong(5, 7)
        request = server.takeRequest()
        assertEquals("PUT", request.method); assertEquals("/api/v1/setlists/5/songs/7", request.path)

        server.enqueue(ok(detail))
        repo.removeSong(5, 7)
        request = server.takeRequest()
        assertEquals("DELETE", request.method); assertEquals("/api/v1/setlists/5/songs/7", request.path)
    }

    @Test fun `adding an unfavorited song surfaces SONG_NOT_FAVORITED`() = runBlocking {
        server.enqueue(json(400, """{"code":"SONG_NOT_FAVORITED","message":"찜한 곡만 세트리스트에 추가할 수 있습니다.","data":null}"""))
        try {
            repo.addSong(5, 9)
            fail("expected BackendRequestException")
        } catch (e: BackendRequestException) {
            assertEquals(400, e.status); assertEquals("SONG_NOT_FAVORITED", e.code)
            assertTrue(e.message!!.contains("찜한 곡만"))
        }
    }
}
