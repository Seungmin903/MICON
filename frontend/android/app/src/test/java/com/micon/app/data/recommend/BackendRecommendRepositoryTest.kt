package com.micon.app.data.recommend

import com.micon.app.core.auth.MemoryTokenStore
import com.micon.app.core.network.BackendClient
import com.micon.app.data.PracticeAssets
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelf
import com.micon.app.data.RecommendShelves
import com.micon.app.data.RecommendedSong
import com.micon.app.data.ShelfKeys
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.voice.VoiceAnalysis
import com.micon.app.data.voice.VoiceAnalysisReport
import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceAnalysisStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 백엔드 추천(`RecommendationResponse`)·적합도(`SongFitResponse`) 매핑과 로컬 폴백 규칙을 고정한다. */
class BackendRecommendRepositoryTest {
    private val catalog = listOf(
        SongSummary(id = 7, title = "빈잔", artist = "남진", imageUrl = null, durationMs = 200_000),
        SongSummary(id = 1, title = "너의 번호를 누르고", artist = "#안녕, 영화처럼", imageUrl = null, durationMs = 251_158),
        SongSummary(id = 3, title = "밤편지", artist = "아이유", imageUrl = null, durationMs = 250_000),
    )

    private class FakeSongs(val items: List<SongSummary>) : SongRepository {
        override suspend fun search(query: String, page: Int, size: Int) = SongPage(items, page, size, items.size.toLong(), 1)
        override suspend fun detail(songId: Long): SongDetail? = null
        override suspend fun practiceAssets(songId: Long, selectedKey: Int): PracticeAssets = throw UnsupportedOperationException()
    }

    private class FakeVoice(var latestAnalysis: VoiceAnalysis?) : VoiceAnalysisRepository {
        override suspend fun upload(file: File, mimeType: String, attemptNo: Int, onProgress: (Float) -> Unit): Long = throw UnsupportedOperationException()
        override suspend fun create(recordingId: Long, songId: Long?, method: String): VoiceAnalysis = throw UnsupportedOperationException()
        override suspend fun get(analysisId: Long): VoiceAnalysis = throw UnsupportedOperationException()
        override suspend fun latest(): VoiceAnalysis? = latestAnalysis
        override suspend fun report(analysisId: Long): VoiceAnalysisReport = throw UnsupportedOperationException()
    }

    /** 로컬 폴백 표식: 섹션 key "local", fit 은 null */
    private object FakeLocal : RecommendRepository {
        override suspend fun fit(songId: Long): SongFit? = null
        override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(true, listOf(RecommendShelf("local", "내 음역에 맞는 곡", null, emptyList<RecommendedSong>())))
    }

    private lateinit var server: MockWebServer
    private lateinit var voice: FakeVoice
    private lateinit var repo: BackendRecommendRepository
    private var loggedIn = true

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = MemoryTokenStore().apply { saveSession(1, "access", "refresh") }
        val api = BackendClient(server.url("/api/v1/").toString(), false, tokens) {}.api
        voice = FakeVoice(VoiceAnalysis(9, 57, null, "SCALE", VoiceAnalysisStatus.COMPLETED, "VALID", null, null, null, "2026-09-14T10:00:00"))
        repo = BackendRecommendRepository(api, FakeSongs(catalog), FakeLocal, isLoggedIn = { loggedIn })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun ok(data: String, code: Int = 200) = json(code, """{"code":"SUCCESS","message":"요청을 처리했습니다.","data":$data}""")

    @Test fun `completed recommendation maps shelves in server order and fills songs from the catalog`() = runBlocking {
        server.enqueue(ok("""{"recommendationId":5,"status":"COMPLETED","progress":100,"scoringVersion":"two-axis-v1","compositionVersion":"quota-v1","shelves":[
            {"key":"discovery","title":"새로운 발견","metric":"안 듣던 장르","order":3,"items":[{"songId":3,"overallRank":9,"position":0,"finalScore":61.5,"reasonText":"가볼 만한 장르"}]},
            {"key":"challenge","title":"고음 도전","metric":null,"order":2,"items":[{"songId":1,"overallRank":4,"position":0}]},
            {"key":"voice","title":"내 음색에 딱인 곡","metric":"ECAPA 상위","order":0,"items":[
                {"songId":7,"overallRank":1,"position":0,"axis":"ECAPA","label":"목소리 물성이 가까운 가수","recommendedKey":-2,"finalScore":0.91,"timbreScore":88.0},
                {"songId":99,"overallRank":2,"position":1,"finalScore":0.8},
                {"songId":1,"overallRank":3,"position":2,"finalScore":0.7,"reasonText":"중심음이 가까워요"}]}]}"""))
        val result = repo.shelves(limit = 10)
        assertEquals("/api/v1/recommendations/latest", server.takeRequest().path)
        assertTrue(result.personalized); assertNull(result.note)
        assertEquals(listOf(ShelfKeys.VOICE, ShelfKeys.DISCOVERY), result.shelves.map { it.key })   // challenge 제외, order 순
        val voiceShelf = result.shelves[0]
        assertEquals(listOf(7L, 1L), voiceShelf.items.map { it.song.id })                             // 카탈로그에 없는 99 는 빠짐
        val first = voiceShelf.items[0]
        assertEquals("빈잔", first.song.title); assertEquals(1, first.rank)
        assertEquals(-2, first.fit?.recommendedKey)
        assertEquals(0.91f, first.fit!!.overallFit, 1e-6f)
        assertEquals(0.88f, first.fit!!.timbreFit!!, 1e-6f)                                         // 0~100 → 0~1
        assertEquals("목소리 물성이 가까운 가수", first.reasons.first())
        assertEquals("two-axis-v1", first.fit!!.scoringVersion)
        assertEquals(listOf("중심음이 가까워요"), voiceShelf.items[1].reasons)
    }

    @Test fun `no recommendation yet requests one and falls back to local with a note`() = runBlocking {
        server.enqueue(json(404, """{"code":"RECOMMENDATION_NOT_FOUND","message":"추천 결과를 찾을 수 없습니다.","data":null}"""))
        server.enqueue(ok("""{"recommendationId":6,"jobId":"j1","status":"GENERATING","progress":0,"generatedAt":"2026-09-14T10:00:00","shelves":[]}""", 202))
        val result = repo.shelves()
        assertEquals("/api/v1/recommendations/latest", server.takeRequest().path)
        val create = server.takeRequest()
        assertEquals("POST", create.method); assertEquals("/api/v1/recommendations", create.path)
        assertEquals("local", result.shelves.single().key)
        assertTrue(result.note!!.contains("만들고 있어요"))
    }

    @Test fun `input not ready and ai disabled fall back with explanatory notes`() = runBlocking {
        server.enqueue(json(404, """{"code":"RECOMMENDATION_NOT_FOUND","message":"없음","data":null}"""))
        server.enqueue(json(409, """{"code":"RECOMMENDATION_INPUT_NOT_READY","message":"완료된 음성 분석과 사용자 임베딩이 필요합니다.","data":null}"""))
        val notReady = repo.shelves()
        assertTrue(notReady.note!!.startsWith(RecommendShelves.NOTE_ANALYSIS_REQUIRED))
        assertTrue("기기 계산 추천이 있으면 그 사실을 덧붙인다", notReady.note!!.contains("이 기기의 음역 계산 추천"))
        server.takeRequest(); server.takeRequest()

        server.enqueue(json(404, """{"code":"RECOMMENDATION_NOT_FOUND","message":"없음","data":null}"""))
        server.enqueue(json(503, """{"code":"RECOMMENDATION_DISPATCH_FAILED","message":"추천 작업을 요청하지 못했습니다.","data":null}"""))
        assertTrue(repo.shelves().note!!.contains("준비되지 않았어요"))
    }

    @Test fun `generating or failed recommendation keeps local shelves with a status note`() = runBlocking {
        server.enqueue(ok("""{"recommendationId":5,"status":"GENERATING","progress":40,"shelves":[]}"""))
        assertTrue(repo.shelves().note!!.contains("만들고 있어요"))
        server.takeRequest()
        server.enqueue(ok("""{"recommendationId":5,"status":"FAILED","failureReason":"임베딩 없음","shelves":[]}"""))
        val failed = repo.shelves()
        assertEquals("local", failed.shelves.single().key)
        assertTrue(failed.note!!.contains("임베딩 없음"))
    }

    @Test fun `guest gets no recommendation and a login note without touching the server`() = runBlocking {
        loggedIn = false
        val r = repo.shelves()
        assertTrue(r.shelves.isEmpty())
        assertEquals(RecommendShelves.NOTE_LOGIN_REQUIRED, r.note)
        assertNull(repo.fit(7))
        assertEquals(0, server.requestCount)
    }

    @Test fun `fit maps AVAILABLE song fit with normalized scores and burden sections`() = runBlocking {
        server.enqueue(ok("""{"profileId":9,"sessionId":3,"profileVersion":1}"""))
        server.enqueue(ok("""{"songId":7,"analysisId":9,"selectedKeyOffset":0,"evaluationStatus":"AVAILABLE",
            "originalKeyFit":{"userStableLowMidi":55,"userStableHighMidi":67,"songLowMidi":57,"songHighMidi":72,"score":66.67},
            "selectedKeyFit":{"userStableLowMidi":55,"userStableHighMidi":67,"songLowMidi":57,"songHighMidi":72,"score":66.67},
            "recommendedKeyOffset":-3,"recommendationScores":{"overall":81.2,"range":66.67,"timbre":90,"rhythm":null,"algorithmVersion":"fit-v1"},
            "reason":"고음 3반음만 내리면 편해요","burdenSections":[{"sectionId":501,"sectionType":"CHORUS","sectionName":"후렴","startMs":60000,"endMs":75000,"difficultyType":"HIGH","difficultyScore":0.8,"description":null}],
            "unavailableReason":null}"""))
        val fit = checkNotNull(repo.fit(7))
        assertEquals("/api/v1/users/me/voice-profile", server.takeRequest().path)                     // 저장 프로필 id 를 먼저 읽는다
        assertEquals("/api/v1/songs/7/fit?analysisId=9&keyOffset=0", server.takeRequest().path)
        assertEquals(-3, fit.recommendedKey)
        assertEquals(0.6667f, fit.rangeFit!!.score, 1e-4f)
        assertEquals(57, fit.rangeFit!!.songCoreLowMidi); assertEquals(72, fit.rangeFit!!.songCoreHighMidi)
        assertEquals(0.812f, fit.overallFit, 1e-4f)
        assertEquals(0.9f, fit.timbreFit!!, 1e-6f); assertNull(fit.rhythmFit)
        assertEquals(listOf("고음 3반음만 내리면 편해요"), fit.reasons)
        val strain = fit.strainSections!!.original.single()
        assertEquals(60.0, strain.startSec, 1e-9); assertEquals(75.0, strain.endSec, 1e-9)
        assertEquals("fit-v1", fit.scoringVersion)
    }

    @Test fun `fit falls back to local when unavailable or without a saved profile`() = runBlocking {
        server.enqueue(ok("""{"profileId":9,"sessionId":3,"profileVersion":1}"""))
        server.enqueue(ok("""{"songId":7,"analysisId":9,"selectedKeyOffset":0,"evaluationStatus":"UNAVAILABLE","burdenSections":[],"unavailableReason":"SONG_RANGE_UNAVAILABLE"}"""))
        assertNull(repo.fit(7))
        server.takeRequest(); server.takeRequest()
        server.enqueue(json(404, """{"code":"VOICE_PROFILE_NOT_FOUND","message":"없음","data":null}"""))
        val fresh = BackendRecommendRepository(BackendClient(server.url("/api/v1/").toString(), false, MemoryTokenStore().apply { saveSession(1, "a", "r") }) {}.api, FakeSongs(catalog), FakeLocal, { true })
        assertNull(fresh.fit(7))
        assertEquals(3, server.requestCount)                                                        // 저장 프로필이 없으면 적합도 API 를 부르지 않는다
    }

    @Test fun `unit normalizes both percent and ratio scales`() {
        assertEquals(0.5f, BackendRecommendRepository.unit(50.0)!!, 1e-6f)
        assertEquals(0.5f, BackendRecommendRepository.unit(0.5)!!, 1e-6f)
        assertEquals(1f, BackendRecommendRepository.unit(1.0)!!, 1e-6f)
        assertNull(BackendRecommendRepository.unit(null))
    }
}
