package com.micon.app.data

import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.Job
import com.micon.app.data.engine.dto.KaraokeNote
import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.RangeFitCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 백엔드 대신 픽스처(엔진 job 14260) 기반 가짜 SongRepository 로 로컬 추천 저장소를 검증한다. 곡 id 는 백엔드 song_id(Long). */
class LocalRecommendRepositoryTest {

    private class FakeSongs(private val notes: List<KaraokeNote>) : SongRepository {
        val analyzed = summary(14260L, "행복하지 말아요")
        val unanalyzed = summary(1L, "분석 안 된 곡")

        override suspend fun search(query: String, page: Int, size: Int) =
            SongPage(listOf(analyzed, unanalyzed).take(size), page, size, 2, 1)

        override suspend fun detail(songId: Long): SongDetail? = when (songId) {
            analyzed.id -> SongDetail(analyzed, listOf("발라드"), analysis = analysis(53, 74), favorite = false, practiceReady = true)
            unanalyzed.id -> SongDetail(unanalyzed, emptyList(), analysis = null, favorite = false, practiceReady = false)
            else -> null
        }

        override suspend fun practiceAssets(songId: Long, selectedKey: Int): PracticeAssets =
            if (songId == analyzed.id) PracticeAssets(
                songId, analyzed.title, selectedKey, InstrumentalMedia(null, null, null, null, null, null),
                lyricsPlain = null, lyricsSynced = null, targetNotes = notes, dataVersion = "test", sections = emptyList(),
            ) else throw PracticeAssetsNotReadyException("연습 데이터가 아직 준비되지 않았습니다.")

        private fun summary(id: Long, title: String) =
            SongSummary(id, title, "가수", null, null, genre = "발라드", releaseDate = "2010-01-01")

        private fun analysis(low: Int, high: Int) =
            SongAnalysis("D major", 144, "4/4", low, high, taskSupport = mapOf("pitch_range" to "pass"))
    }

    private val notes: List<KaraokeNote> by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/tj-14260.job.json"))
        checkNotNull(EngineClient.moshi.adapter(Job::class.java).fromJson(stream.bufferedReader().readText())?.result?.karaokeNotes)
    }
    private val songs by lazy { FakeSongs(notes) }
    private val repo by lazy { LocalRecommendRepository(songs) }

    @After fun reset() = UserStore.setProfile(null)

    @Test
    fun `no profile means not evaluable and default recommendation`() = runBlocking {
        UserStore.setProfile(null)
        assertNull(repo.fit(14260L))
        val r = repo.recommend(RecommendMode.RANGE, 5)
        assertFalse(r.personalized)
        assertEquals(2, r.items.size)                   // 백엔드 목록은 연습 가능 여부로 걸러 주지 않는다 → 기본 추천은 목록 전체
        assertNull(r.items[0].fit)
        assertTrue(r.items[0].reasons.isNotEmpty())
    }

    @Test
    fun `fit is computed from notes and cached for list consistency`() = runBlocking {
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 55, comfortHigh = 67))
        val fit = repo.fit(14260L)
        assertNotNull(fit)
        fit!!
        assertTrue(fit.recommendedKey!! in RangeFitCalculator.KEY_SHIFTS)
        assertEquals(fit.rangeFit!!.score, fit.overallFit)
        assertTrue(fit.reasons.isNotEmpty())
        assertNull(fit.timbre)
        assertNotNull(fit.strainSections)

        val r = repo.recommend(RecommendMode.RANGE, 5)
        assertTrue(r.personalized)
        assertEquals(1, r.items[0].rank)
        assertSame(fit, r.items[0].fit)                 // 목록과 상세가 같은 객체(REC-005)
    }

    @Test
    fun `songs without analysis or unknown ids are not evaluable`() = runBlocking {
        UserStore.setProfile(UserStore.VoiceProfile())
        assertNull(repo.fit(1L))                        // ERR-004
        assertNull(repo.fit(999L))
    }

    @Test
    fun `profile change invalidates the cache`() = runBlocking {
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 50, comfortHigh = 60))
        val low = repo.fit(14260L)!!
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 60, comfortHigh = 72))
        val high = repo.fit(14260L)!!
        assertTrue(high.recommendedKey!! > low.recommendedKey!!)   // 높은 목소리엔 더 높은 키
    }
}
