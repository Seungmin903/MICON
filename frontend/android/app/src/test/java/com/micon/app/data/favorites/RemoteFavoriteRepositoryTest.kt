package com.micon.app.data.favorites

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.backend.AuthSessionExpiredException
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

/** 백엔드 `FavoriteController` 계약과 낙관적 토글의 되돌림을 고정한다. */
class RemoteFavoriteRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteFavoriteRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemoteFavoriteRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String) = json(200, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")

    @Test fun `uuid favorites keep their identity when refreshed and removed`() = runBlocking {
        val id = "d221afea-6dc1-5223-a6bc-68fa055b4820"
        server.enqueue(ok("""{"songs":[{"songId":"$id","title":"곡","artists":["가수"]}],"totalCount":1}"""))
        assertEquals(id, repo.refresh().single().id)
        server.takeRequest()
        assertEquals(setOf(id), repo.likedIds.value)
        server.enqueue(ok("null"))
        assertFalse(repo.toggle(id))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/favorites/$id", request.path)
        assertTrue(repo.likedIds.value.isEmpty())
    }

    @Test fun `refresh fills liked cache from FavoriteResponse`() = runBlocking {
        server.enqueue(ok("""{"songs":[{"songId":7,"title":"빈잔","artists":["남진"],"imageUrl":null,"durationMs":200000},
            {"songId":1,"title":"너의 번호를 누르고","artists":["#안녕","영화처럼"],"imageUrl":"https://img/1.jpg","durationMs":251158}],"totalCount":2}"""))
        val songs = repo.refresh()
        val request = server.takeRequest()
        assertEquals("/api/v1/favorites", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals(listOf("7", "1"), songs.map { it.id })
        assertEquals("#안녕, 영화처럼", songs[1].artist)
        assertEquals(setOf("7", "1"), repo.likedIds.value)
    }

    @Test fun `toggle adds with PUT and removes with DELETE`() = runBlocking {
        server.enqueue(ok("null"))
        assertTrue(repo.toggle("7"))
        var request = server.takeRequest()
        assertEquals("PUT", request.method); assertEquals("/api/v1/favorites/7", request.path)
        assertEquals(setOf("7"), repo.likedIds.value)

        server.enqueue(ok("null"))
        assertFalse(repo.toggle("7"))
        request = server.takeRequest()
        assertEquals("DELETE", request.method); assertEquals("/api/v1/favorites/7", request.path)
        assertTrue(repo.likedIds.value.isEmpty())
    }

    @Test fun `failed toggle reverts the optimistic change and rethrows`() = runBlocking {
        server.enqueue(json(404, """{"code":"SONG_NOT_FOUND","message":"곡을 찾을 수 없습니다.","data":null}"""))
        try {
            repo.toggle("99")
            fail("expected BackendRequestException")
        } catch (e: BackendRequestException) {
            assertEquals("SONG_NOT_FOUND", e.code)
        }
        assertTrue(repo.likedIds.value.isEmpty())
    }

    @Test fun `guest 401 surfaces as session expired`() = runBlocking {
        // BackendClient 는 401 을 받으면 먼저 auth/refresh 를 시도한다 → 갱신도 401 이면 세션 만료로 처리한다
        server.enqueue(json(401, """{"code":"UNAUTHENTICATED","message":"인증이 필요합니다.","data":null}"""))
        server.enqueue(json(401, """{"code":"UNAUTHENTICATED","message":"인증이 필요합니다.","data":null}"""))
        try {
            repo.refresh()
            fail("expected AuthSessionExpiredException")
        } catch (_: AuthSessionExpiredException) {
        }
        repo.clear()
        assertTrue(repo.likedIds.value.isEmpty())
    }
}
