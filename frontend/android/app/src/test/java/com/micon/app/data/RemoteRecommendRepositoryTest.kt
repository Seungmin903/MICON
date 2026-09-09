package com.micon.app.data

import com.micon.app.data.engine.EngineClient
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import com.micon.app.data.recommend.RecommendGateway
import com.micon.app.data.recommend.RecommendMapper
import com.micon.app.data.recommend.RemoteRecommendRepository
import com.micon.app.data.recommend.dto.RecommendJobDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 서버 샘플 응답(ai/recommend-server/docs/sample-response.json 복사본, shelves-v1)으로 DTO 파싱과 SongFit 매핑을 고정한다. */
class RemoteRecommendRepositoryTest {
    private val job: RecommendJobDto by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/recommend-sample.json"))
        checkNotNull(EngineClient.moshi.adapter(RecommendJobDto::class.java).fromJson(stream.bufferedReader().readText()))
    }

    /** 곡 카탈로그가 비어 있는 상황: 요약은 서버 metadata 로 만들어진다. */
    private object EmptySongs : SongRepository {
        override suspend fun search(query: String, analyzedOnly: Boolean, limit: Int, offset: Int) = emptyList<SongSummary>()
        override suspend fun detail(songId: String): SongDetail? = null
        override suspend fun karaokeNotes(songId: String) = null
        override suspend fun counts() = SongRepository.Counts(0, 0)
    }

    private class FixtureGateway(private val job: RecommendJobDto) : RecommendGateway {
        var lastMode: String? = null
        var lastGenres: List<String> = emptyList()
        var lastArtists: List<String> = emptyList()
        override suspend fun recommend(audio: File, topK: Int, mode: String, genres: List<String>, preferredArtists: List<String>): RecommendJobDto {
            lastMode = mode; lastGenres = genres; lastArtists = preferredArtists
            return job.copy(status = "queued", result = null)
        }
        override suspend fun awaitJob(jobId: String) = job
    }

    @After fun reset() { UserStore.setProfile(null); UserStore.preferredGenres = emptySet(); UserStore.preferredArtists = emptySet() }

    @Test
    fun `sample response parses with server field names`() {
        assertEquals("complete", job.status)
        val result = checkNotNull(job.result)
        // shelves-v1: 섹션 4개(challenge 는 기본 꺼짐), 섹션 간 곡 중복 없음, recommendations 는 첫 섹션 복사본
        assertEquals(listOf("voice", "original_key", "favorite_artists", "discovery"), result.shelves.map { it.key })
        val all = result.shelves.flatMap { s -> s.items.map { it.songId } }
        assertEquals(all.size, all.toSet().size)
        assertEquals(result.shelves[0].items.map { it.songId }, result.recommendations.map { it.songId })
        val recs = result.recommendations
        assertEquals((1..recs.size).toList(), recs.map { it.position })
        assertTrue(recs.all { it.songId!!.startsWith("tj-") && it.shelf == "voice" })
        assertTrue(recs.map { it.finalScore } == recs.map { it.finalScore }.sortedDescending())   // 서버가 정한 순서
        assertTrue(result.shelves[1].items.all { it.axis == null && it.label == null })          // 음색은 voice 섹션에서만
        assertEquals(listOf("ko"), result.algorithm!!.languageGate!!.allowed)
    }

    @Test
    fun `dto maps to SongFit without client-side recalculation`() {
        val dto = job.result!!.recommendations.first()
        val fit = RecommendMapper.toFit(dto.songId!!, dto, strain = null)
        assertEquals(dto.finalScore, fit.overallFit)
        assertEquals(dto.recommendedKey, fit.recommendedKey)
        assertEquals(dto.axis, fit.timbre!!.axis.name)
        assertEquals(dto.label, fit.reasons.first())
        assertNotNull(fit.rangeFit)
        assertEquals(dto.rangeFit!!.score, fit.rangeFit!!.score)
        assertEquals(dto.compositionVersion, fit.compositionVersion)
        assertNull(fit.strainSections)                       // 서버는 부담 구간을 내려주지 않음
        // 음색 없는 섹션: timbre = 평가 불가(null), 사유는 그래도 최소 1개(REC-002)
        val plain = job.result!!.shelves[1].items.first()
        val plainFit = RecommendMapper.toFit(plain.songId!!, plain, strain = null)
        assertNull(plainFit.timbre)
        assertTrue(plainFit.reasons.isNotEmpty())
    }

    @Test
    fun `remote repository keeps server order and exposes fits for detail`() = runBlocking {
        UserStore.preferredGenres = setOf("발라드", "팝")
        val gateway = FixtureGateway(job)
        val repo = RemoteRecommendRepository(gateway, EmptySongs, LocalRecommendRepository(EmptySongs)) { File("dummy.wav") }
        val r = repo.recommend(RecommendMode.TIMBRE, 5)
        assertEquals("timbre", gateway.lastMode)
        assertEquals(listOf("발라드", "팝"), gateway.lastGenres)
        assertTrue(r.personalized)
        assertEquals(listOf("ko"), r.languageGateAllowed)
        assertEquals(job.result!!.recommendations.map { it.songId }, r.items.map { it.song.id })
        assertEquals((1..r.items.size).toList(), r.items.map { it.rank })
        assertEquals("이름 모를 소녀", r.items[0].song.title)          // 카탈로그에 없어도 metadata 로 요약
        assertEquals(r.items[0].fit, repo.fit(r.items[0].song.id))   // 목록·상세 동일(REC-005)
    }

    @Test
    fun `shelves keep server sections and order and drop challenge`() = runBlocking {
        UserStore.preferredArtists = setOf("이하이", "한로로")
        val gateway = FixtureGateway(job)
        val repo = RemoteRecommendRepository(gateway, EmptySongs, LocalRecommendRepository(EmptySongs)) { File("dummy.wav") }
        val r = repo.shelves(8)
        assertEquals(listOf("이하이", "한로로"), gateway.lastArtists)
        assertTrue(r.personalized)
        val server = job.result!!.shelves
        assertEquals(server.map { it.key }, r.shelves.map { it.key })
        assertEquals(server.map { it.title }, r.shelves.map { it.title })
        assertTrue(r.shelves.none { it.key == ShelfKeys.CHALLENGE })
        r.shelves.zip(server).forEach { (mine, theirs) -> assertEquals(theirs.items.map { it.songId }, mine.items.map { it.song.id }) }
        val any = r.shelves[2].items.first()
        assertEquals(any.fit, repo.fit(any.song.id))                  // 어느 섹션의 곡이든 상세가 같은 값
    }

    @Test
    fun `without a recording or for catalog modes it falls back to local`() = runBlocking {
        val gateway = FixtureGateway(job)
        val repo = RemoteRecommendRepository(gateway, EmptySongs, LocalRecommendRepository(EmptySongs)) { null }
        val r = repo.recommend(RecommendMode.RANGE, 5)
        assertFalse(r.personalized)
        assertNull(gateway.lastMode)
        val byGenre = RemoteRecommendRepository(gateway, EmptySongs, LocalRecommendRepository(EmptySongs)) { File("x.wav") }.recommend(RecommendMode.GENRE, 5)
        assertNull(gateway.lastMode)
        assertFalse(byGenre.personalized)
        val shelves = repo.shelves(5)                                  // 녹음 없음 → 로컬 기본 추천 한 섹션
        assertFalse(shelves.personalized)
        assertEquals(1, shelves.shelves.size)
        assertNull(gateway.lastMode)
    }
}
