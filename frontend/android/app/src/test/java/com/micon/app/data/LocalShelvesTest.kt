package com.micon.app.data

import com.micon.app.data.local.LocalRecommendRepository
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오프라인 추천 섹션(shelves) 규칙: 섹션끼리 곡이 겹치지 않고, 한 섹션이 곡을 다 가져가 뒷 섹션을 굶기지 않으며,
 * "새로운 발견"은 다시 뽑을 때마다 순서가 달라진다(제목 옆 새로고침 버튼이 이 값에 기댄다).
 */
class LocalShelvesTest {
    /** 분석된 곡 40개. 음역·장르·가수·발매연도를 조금씩 달리해 섹션들이 실제로 채워지게 한다. */
    private class ManySongs(private val n: Int = 40) : SongRepository {
        private val genres = listOf("발라드", "Rock", "K-Pop", "Folk", "Hip-Hop")
        private val artists = listOf("가수A", "가수B", "한로로", "백아", "가수E")

        private fun summary(i: Int) = SongSummary(
            id = i.toLong(), title = "곡$i", artist = artists[i % artists.size], imageUrl = null, durationMs = null,
            genre = genres[i % genres.size], releaseDate = "%04d-01-01".format(1990 + i % 35),
        )

        override suspend fun search(query: String, page: Int, size: Int) =
            SongPage((0 until n).map(::summary).drop(page * size).take(size), page, size, n.toLong(), (n + size - 1) / size)

        override suspend fun detail(songId: Long): SongDetail? {
            val i = songId.toInt()
            if (i !in 0 until n) return null
            val low = 55 + i % 5                      // 사용자 안정 음역(55~67) 안팎으로 조금씩 다르게
            return SongDetail(summary(i), listOf(genres[i % genres.size]), analysis = analysis(low, low + 9), favorite = false, practiceReady = false)
        }

        /** 음표 없음(409) → 곡 주요 음역으로 계산 */
        override suspend fun practiceAssets(songId: Long, selectedKey: Int): PracticeAssets = throw PracticeAssetsNotReadyException("not ready")

        private fun analysis(low: Int, high: Int) =
            SongAnalysis("C major", 120, "4/4", low, high, low, high, taskSupport = mapOf("pitch_range" to "pass"))
    }

    private val repo = LocalRecommendRepository(ManySongs())

    @After fun reset() { UserStore.setProfile(null); UserStore.preferredArtists = emptySet(); UserStore.preferredGenres = emptySet() }

    private fun seeded() {
        UserStore.setProfile(UserStore.VoiceProfile(comfortLow = 55, comfortHigh = 67))
        UserStore.preferredArtists = setOf("한로로", "백아")
    }

    @Test
    fun `sections share the pool instead of the first one taking everything`() = runBlocking {
        seeded()
        val r = repo.shelves(limit = 30)
        assertTrue("섹션이 2개 이상이어야 한다: ${r.shelves.map { it.key }}", r.shelves.size >= 2)
        assertTrue("빈 섹션이 있으면 안 된다", r.shelves.all { it.items.isNotEmpty() })
        val all = r.shelves.flatMap { s -> s.items.map { it.song.id } }
        assertEquals("섹션끼리 곡이 겹치면 안 된다", all.size, all.toSet().size)
    }

    @Test
    fun `discovery is reshuffled every time you ask again`() = runBlocking {
        seeded()
        fun discovery(reshuffle: Int) = runBlocking {
            repo.shelves(limit = 30, reshuffle = reshuffle).shelves.first { it.key == ShelfKeys.DISCOVERY }.items.map { it.song.id }
        }
        val first = discovery(0)
        assertTrue("새로운 발견에 곡이 있어야 섞임을 볼 수 있다", first.size >= 3)
        assertEquals("같은 값으로 다시 부르면 같은 결과", first, discovery(0))
        // 연속된 값이라도 순서가 달라야 한다 (예전엔 인접 seed 가 같은 순서를 내 새로고침이 먹통이었다)
        assertNotEquals(first, discovery(1))
        assertNotEquals(discovery(1), discovery(2))
    }

    @Test
    fun `no profile means no recommendation and an analysis-required note`() = runBlocking {
        UserStore.setProfile(null)
        val r = repo.shelves(limit = 10)
        assertEquals(false, r.personalized)
        assertTrue("분석 전에는 기본 추천을 보이지 않는다", r.shelves.isEmpty())
        assertEquals(RecommendShelves.NOTE_ANALYSIS_REQUIRED, r.note)
    }
}
