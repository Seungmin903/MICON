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

/** sqlite 대신 픽스처(tj-14260) 기반 가짜 SongRepository 로 로컬 추천 저장소를 검증한다. */
class LocalRecommendRepositoryTest {

    private class FakeSongs(private val notes: List<KaraokeNote>) : SongRepository {
        val analyzed = summary("tj-14260", "행복하지 말아요", analyzed = true)
        val unanalyzed = summary("tj-00001", "분석 안 된 곡", analyzed = false)

        override suspend fun search(query: String, analyzedOnly: Boolean, limit: Int, offset: Int, language: String?, chartTag: String?, sort: SongRepository.Sort) =
            (if (analyzedOnly) listOf(analyzed) else listOf(analyzed, unanalyzed)).take(limit)
        override suspend fun chartTagCounts(language: String?) = emptyMap<String, Int>()

        override suspend fun detail(songId: String): SongDetail? = when (songId) {
            analyzed.id -> SongDetail(analyzed, null, analysis = analysis(53, 74), hasInstrumental = true, renderedKeyShifts = emptyList())
            unanalyzed.id -> SongDetail(unanalyzed, null, analysis = null, hasInstrumental = false, renderedKeyShifts = emptyList())
            else -> null
        }

        override suspend fun karaokeNotes(songId: String) = if (songId == analyzed.id) notes else null
        override suspend fun counts() = SongRepository.Counts(2, 1)

        private fun summary(id: String, title: String, analyzed: Boolean) =
            SongSummary(id, null, title, "가수", null, null, null, "발라드", "ko", "2010-01-01", analyzed)

        private fun analysis(low: Int, high: Int) = SongAnalysis(
            null, null, "D major", null, 144, "4/4", low, high, null, null, null, null, null, null, null, null,
            taskSupport = mapOf("pitch_range" to "pass"), register = null, beatGrid = null, scoreOctaveShift = null,
        )
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
        assertNull(repo.fit("tj-14260"))
        val r = repo.recommend(RecommendMode.RANGE, 5)
        assertFalse(r.personalized)
        assertEquals(1, r.items.size)
        assertNull(r.items[0].fit)
        assertTrue(r.items[0].reasons.isNotEmpty())
    }

    @Test
    fun `fit is computed from notes and cached for list consistency`() = runBlocking {
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 55, comfortHigh = 67))
        val fit = repo.fit("tj-14260")
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
        assertNull(repo.fit("tj-00001"))               // ERR-004
        assertNull(repo.fit("nope"))
    }

    @Test
    fun `profile change invalidates the cache`() = runBlocking {
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 50, comfortHigh = 60))
        val low = repo.fit("tj-14260")!!
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 60, comfortHigh = 72))
        val high = repo.fit("tj-14260")!!
        assertTrue(high.recommendedKey!! > low.recommendedKey!!)   // 높은 목소리엔 더 높은 키
    }
}
