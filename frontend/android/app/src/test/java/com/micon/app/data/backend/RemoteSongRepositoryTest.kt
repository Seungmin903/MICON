package com.micon.app.data.backend

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.PracticeAssetsNotReadyException
import com.micon.app.data.UnsupportedPracticeKeyException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 백엔드 곡 API 계약(`SongResponse`·`PageResponse`·`PracticeAssetResponse`, `ApiResponse` 봉투)을 앱 모델로 매핑하는 것을 고정한다.
 * 응답 JSON 은 backend/src/main/java/com/micon/backend/song|practice/api 의 record 필드명 그대로다.
 */
class RemoteSongRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteSongRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        repo = RemoteSongRepository(BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String) = json(200, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")

    @Test fun `uuid from search is preserved in detail and practice assets`() = runBlocking {
        val id = "d221afea-6dc1-5223-a6bc-68fa055b4820"
        server.enqueue(ok("""{"content":[{"songId":"$id","title":"...사랑했잖아...","artists":["린"]}],"page":0,"size":1,"totalElements":948,"totalPages":948}"""))
        val song = repo.search("사랑", size = 1).items.single()
        assertEquals(id, song.id)
        assertEquals("린", song.artist)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/songs?"))

        server.enqueue(ok("""{"songId":"$id","title":"...사랑했잖아...","artists":["린"],"practiceReady":true}"""))
        assertEquals(id, repo.detail(song.id)!!.summary.id)
        assertEquals("/api/v1/songs/$id", server.takeRequest().path)

        server.enqueue(ok("""{"songId":"$id","title":"...사랑했잖아...","selectedKey":0}"""))
        assertEquals(id, repo.practiceAssets(song.id).songId)
        assertEquals("/api/v1/songs/$id/practice-assets?selectedKey=0", server.takeRequest().path)
    }

    @Test fun `song list maps PageResponse and joins artists`() = runBlocking {
        server.enqueue(ok("""{"content":[{"songId":1,"title":"너의 번호를 누르고","artists":["#안녕","영화처럼"],"imageUrl":null,"durationMs":251158}],"page":0,"size":20,"totalElements":384,"totalPages":20}"""))
        val page = repo.search("번호", page = 0, size = 20)
        val request = server.takeRequest()
        assertEquals("/api/v1/songs?query=%EB%B2%88%ED%98%B8&page=0&size=20", request.path)
        assertNull(request.getHeader("Authorization")) // Public search does not require a member token.
        assertEquals(384L, page.totalElements)
        val song = page.items.single()
        assertEquals("1", song.id)
        assertEquals("#안녕, 영화처럼", song.artist)
        assertEquals(251158L, song.durationMs)
        assertNull(song.imageUrl)
    }

    @Test fun `song detail maps analysis and readiness`() = runBlocking {
        server.enqueue(ok("""{"songId":7,"title":"빈잔","artists":["남진"],"albumName":"남진 골든","imageUrl":"https://img/7.jpg","releaseDate":"1982-03-01","durationMs":200000,"originalKey":"E♭ major","bpm":96.5,"genres":["트로트","발라드"],"lowestMidi":50.0,"highestMidi":69.0,"favorite":true,"practiceReady":true}"""))
        val detail = checkNotNull(repo.detail("7"))
        assertEquals("/api/v1/songs/7", server.takeRequest().path)
        assertEquals("남진", detail.summary.artist)
        assertEquals("트로트", detail.summary.genre)
        assertEquals("1982-03-01", detail.summary.releaseDate)
        assertEquals(listOf("트로트", "발라드"), detail.genres)
        assertTrue(detail.favorite)
        assertTrue(detail.practiceReady && detail.hasInstrumental)
        val a = checkNotNull(detail.analysis)
        assertEquals("E♭ major", a.keyName)
        assertEquals(97, a.tempoBpm)                 // BigDecimal 96.5 → 반올림
        assertEquals(50, a.lowestMidi); assertEquals(69, a.highestMidi)
        assertNull(a.timeSignature)                  // 상세에는 박자표가 없다 (practice-assets 확장 항목)
    }

    @Test fun `song without analysis values has null analysis and 404 is null`() = runBlocking {
        server.enqueue(ok("""{"songId":8,"title":"x","artists":[],"genres":[],"favorite":false,"practiceReady":false}"""))
        assertNull(checkNotNull(repo.detail("8")).analysis)
        server.enqueue(json(404, """{"code":"SONG_NOT_FOUND","message":"곡을 찾을 수 없습니다.","data":null}"""))
        assertNull(repo.detail("9"))
    }

    @Test fun `practice assets parse target notes lyrics and sections`() = runBlocking {
        server.enqueue(ok("""{
            "songId":1,"title":"너의 번호를 누르고","selectedKey":0,
            "instrumental":{"mediaId":11,"filePath":"catalog/x/instrumental/1/instrumental.opus","mimeType":"audio/ogg","fileBytes":4200000,"sha256":"ab"},
            "lyricsPlain":"첫 줄\n둘째 줄",
            "lyricsSynced":"[00:16.28] 첫 줄\n[00:23.87] 둘째 줄",
            "targetNotes":[{"sourceStart":16.28,"sourceEnd":16.9,"midi":62,"lyric":"첫","lyricStart":16.28,"lyricEnd":16.9},{"sourceStart":16.9,"sourceEnd":17.4,"midi":64,"lyric":"줄"}],
            "dataVersion":"karaoke-1.0",
            "sections":[{"sectionId":2,"sectionType":"VOCAL","sectionName":"보컬 구간","startMs":16280,"endMs":232360,"sequenceNo":2,"difficultyType":"NONE","difficultyScore":null,"confidence":null,"description":null},
                        {"sectionId":1,"sectionType":"INTRO","sectionName":"인트로","startMs":0,"endMs":16280,"sequenceNo":1,"difficultyType":"NONE","difficultyScore":null,"confidence":null,"description":null}]
        }"""))
        val assets = repo.practiceAssets("1", 0)
        assertEquals("/api/v1/songs/1/practice-assets?selectedKey=0", server.takeRequest().path)
        assertEquals(2, assets.targetNotes.size)
        assertEquals(62, assets.targetNotes[0].midi)
        assertEquals("첫", assets.targetNotes[0].lyric)
        assertEquals("[00:16.28] 첫 줄\n[00:23.87] 둘째 줄", assets.lyricsSynced)
        assertEquals(listOf("INTRO", "VOCAL"), assets.sections.map { it.sectionType })   // sequenceNo 순
        assertEquals("catalog/x/instrumental/1/instrumental.opus", assets.instrumental.filePath)
        assertNull(assets.instrumental.playbackUrl)   // 백엔드가 아직 재생 URL 을 주지 않는다 → 화면은 "반주 준비 중"
        assertNull(assets.beatGrid); assertNull(assets.timeSignature)
        assertFalse(assets.title.isEmpty())
    }

    @Test fun `practice assets 409 and 422 become typed exceptions with server message`() = runBlocking {
        server.enqueue(json(409, """{"code":"PRACTICE_ASSET_NOT_READY","message":"연습 데이터가 아직 준비되지 않았습니다.","data":null}"""))
        try { repo.practiceAssets("1", 0); fail("409 must throw") } catch (e: PracticeAssetsNotReadyException) {
            assertEquals("연습 데이터가 아직 준비되지 않았습니다.", e.message)
        }
        assertNull(repo.karaokeNotesOrNull(server))
        server.enqueue(json(422, """{"code":"UNSUPPORTED_PRACTICE_KEY","message":"지원하지 않는 연습 키입니다.","data":null}"""))
        try { repo.practiceAssets("1", 3); fail("422 must throw") } catch (e: UnsupportedPracticeKeyException) {
            assertNotNull(e.message)
        }
    }

    @Test fun `instrumental uses lightweight mr endpoint and keeps expiration`() = runBlocking {
        val id = "d221afea-6dc1-5223-a6bc-68fa055b4820"
        server.enqueue(ok("""{"songId":"$id","selectedKey":-3,"instrumental":{
            "mediaId":91,"filePath":"catalog/x/instrumental/$id/instrumental_key-3.opus","mimeType":"audio/ogg",
            "fileBytes":4100000,"sha256":"abc","playbackUrl":"https://s3.example/key-3",
            "playbackUrlExpiresAt":"2026-09-21T02:00:00Z"}}"""))

        val media = repo.instrumental(id, -3)

        assertEquals("/api/v1/songs/$id/mr?selectedKey=-3", server.takeRequest().path)
        assertEquals("https://s3.example/key-3", media.playbackUrl)
        assertEquals("2026-09-21T02:00:00Z", media.playbackUrlExpiresAt)
    }

    /** karaokeNotes 의 기본 구현이 409 를 null 로 바꾸는지 (서버에 409 응답을 하나 더 준비해 확인) */
    private suspend fun RemoteSongRepository.karaokeNotesOrNull(server: MockWebServer): List<Any>? {
        server.enqueue(json(409, """{"code":"PRACTICE_ASSET_NOT_READY","message":"준비 중","data":null}"""))
        return karaokeNotes("1")
    }

    @Test fun `other errors surface status and code`() = runBlocking {
        server.enqueue(json(500, """{"code":"INTERNAL_ERROR","message":"서버 오류","data":null}"""))
        try { repo.search("x"); fail("500 must throw") } catch (e: BackendRequestException) {
            assertEquals(500, e.status); assertEquals("INTERNAL_ERROR", e.code); assertEquals("서버 오류", e.message)
        }
    }
}
