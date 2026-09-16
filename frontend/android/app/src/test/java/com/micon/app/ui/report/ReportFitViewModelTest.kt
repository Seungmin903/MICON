package com.micon.app.ui.report

import com.micon.app.data.PracticeAssets
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelves
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportFitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = listOf(SongSummary(1, "너의 번호를 누르고", "#안녕, 영화처럼", null, 251_158), SongSummary(7, "빈잔", "남진", null, 200_000))
    private val liked = listOf(SongSummary(7, "빈잔", "남진", null, 200_000))

    private class FakeSongs(val items: List<SongSummary>) : SongRepository {
        var lastQuery: String? = null
        override suspend fun search(query: String, page: Int, size: Int): SongPage {
            lastQuery = query
            val hit = if (query.isBlank()) items else items.filter { it.title.contains(query) }
            return SongPage(hit, page, size, hit.size.toLong(), 1)
        }
        override suspend fun detail(songId: Long): SongDetail? = null
        override suspend fun practiceAssets(songId: Long, selectedKey: Int): PracticeAssets = throw UnsupportedOperationException()
    }

    private class FakeFavorites(val songs: List<SongSummary>) : FavoriteRepository {
        var refreshes = 0
        override val likedIds: StateFlow<Set<Long>> = MutableStateFlow(songs.map { it.id }.toSet())
        override suspend fun refresh(): List<SongSummary> { refreshes++; return songs }
        override suspend fun toggle(songId: Long): Boolean = throw UnsupportedOperationException()
        override fun clear() {}
    }

    private class FakeRecommend(val fits: Map<Long, SongFit?>) : RecommendRepository {
        override suspend fun fit(songId: Long): SongFit? = fits[songId]
        override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList())
    }

    private fun fit(songId: Long) = SongFit(
        songId = songId, timbre = null, timbreFit = null, rhythmFit = null, rangeFit = null, recommendedKey = -2, strainSections = null,
        overallFit = 0.8f, reasons = listOf("키 -2 로 편해요"), scoringVersion = "test", compositionVersion = null,
    )

    @Before fun setUp() { Dispatchers.setMain(dispatcher); UserStore.setProfile(null) }
    @After fun tearDown() { Dispatchers.resetMain(); UserStore.setProfile(null) }

    @Test fun `logged in picker lists favorites first, search hits the catalog`() = runTest {
        val songs = FakeSongs(catalog); val favorites = FakeFavorites(liked)
        val vm = ReportFitViewModel(songs, favorites, FakeRecommend(emptyMap()), loggedIn = true)
        vm.openPicker(); advanceUntilIdle()
        assertTrue(vm.state.value.pickerOpen)
        assertTrue(vm.state.value.fromFavorites)
        assertEquals(listOf(7L), vm.state.value.candidates.map { it.id })
        assertEquals(1, favorites.refreshes)

        vm.setQuery("번호"); advanceUntilIdle()
        assertFalse(vm.state.value.fromFavorites)
        assertEquals("번호", songs.lastQuery)
        assertEquals(listOf(1L), vm.state.value.candidates.map { it.id })
    }

    @Test fun `guest picker uses the catalog without touching favorites`() = runTest {
        val favorites = FakeFavorites(liked)
        val vm = ReportFitViewModel(FakeSongs(catalog), favorites, FakeRecommend(emptyMap()), loggedIn = false)
        vm.openPicker(); advanceUntilIdle()
        assertEquals(0, favorites.refreshes)
        assertFalse(vm.state.value.fromFavorites)
        assertEquals(2, vm.state.value.candidates.size)
    }

    @Test fun `selecting a song closes the picker and loads its fit`() = runTest {
        val vm = ReportFitViewModel(FakeSongs(catalog), FakeFavorites(liked), FakeRecommend(mapOf(7L to fit(7))), loggedIn = true)
        vm.openPicker(); advanceUntilIdle()
        vm.select(liked[0]); advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.pickerOpen)
        assertEquals(7L, s.selected?.id)
        assertNotNull(s.fit); assertEquals(-2, s.fit?.recommendedKey); assertNull(s.fitNote); assertFalse(s.fitLoading)
    }

    @Test fun `missing fit explains why - no profile vs no song range`() = runTest {
        val vm = ReportFitViewModel(FakeSongs(catalog), FakeFavorites(liked), FakeRecommend(mapOf(1L to null)), loggedIn = true)
        vm.select(catalog[0]); advanceUntilIdle()
        assertNull(vm.state.value.fit)
        assertTrue(vm.state.value.fitNote!!.contains("목소리를 먼저 분석"))

        UserStore.setProfile(UserStore.VoiceProfile())
        vm.select(catalog[0]); advanceUntilIdle()
        assertTrue(vm.state.value.fitNote!!.contains("음역 정보가 아직 없어"))
    }
}
