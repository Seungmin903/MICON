package com.micon.app.ui.songlist

import com.micon.app.data.PracticeAssets
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelves
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.practice.PracticePage
import com.micon.app.data.practice.PracticeRecord
import com.micon.app.data.practice.PracticeReport
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.PracticeResultInput
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 연습 탭 곡 선택: 세 갈래(추천곡·찜한 곡·최근 연습)와 검색. */
@OptIn(ExperimentalCoroutinesApi::class)
class SongListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeSongs(total: Int) : SongRepository {
        val all = (1..total).map { SongSummary("s$it", if (it % 10 == 0) "비 오는 날 $it" else "곡 $it", "가수 ${it % 7}", null, 200_000) }
        val requests = mutableListOf<Pair<String, Int>>()
        override suspend fun search(query: String, page: Int, size: Int): SongPage {
            requests += query to page
            val hit = if (query.isBlank()) all else all.filter { it.title.contains(query) }
            val items = hit.drop(page * size).take(size)
            return SongPage(items, page, size, hit.size.toLong(), (hit.size + size - 1) / size)
        }
        override suspend fun detail(songId: String): SongDetail? = null
        override suspend fun practiceAssets(songId: String, selectedKey: Int): PracticeAssets = throw UnsupportedOperationException()
    }

    private val recommend = object : RecommendRepository {
        override suspend fun fit(songId: String): SongFit? = null
        override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList(), note = RecommendShelves.NOTE_LOGIN_REQUIRED)
    }

    private class FakeFavorites(val songs: List<SongSummary>) : FavoriteRepository {
        override val likedIds: StateFlow<Set<String>> = MutableStateFlow(songs.map { it.id }.toSet())
        override suspend fun refresh(): List<SongSummary> = songs
        override suspend fun toggle(songId: String): Boolean = true
        override fun clear() {}
    }

    private object NoPractices : PracticeRepository {
        override suspend fun history(page: Int, size: Int, date: String?) = PracticePage(emptyList(), 0, size, 0, 0)
        override suspend fun create(songId: String, selectedKey: Int, practiceScope: String): PracticeRecord = error("unused")
        override suspend fun complete(practiceId: Long, durationMs: Int, finalSelectedKey: Int?, keyChangeCount: Int?): PracticeRecord = error("unused")
        override suspend fun cancel(practiceId: Long): PracticeRecord = error("unused")
        override suspend fun savePerformance(practiceId: Long, result: PracticeResultInput): PracticeReport = error("unused")
        override suspend fun report(practiceId: Long): PracticeReport? = null
        override suspend fun delete(practiceId: Long) = Unit
    }

    private fun vm(songs: FakeSongs, favorites: List<SongSummary> = emptyList(), loggedIn: Boolean = true) =
        SongListViewModel(songs, recommend, FakeFavorites(favorites), NoPractices) { loggedIn }

    @Test fun `recommend source falls back to the catalog when there are no shelves`() = runTest {
        val songs = FakeSongs(120)
        val vm = vm(songs); advanceUntilIdle()
        assertEquals(50, vm.state.value.songs.size)
        assertEquals(RecommendShelves.NOTE_LOGIN_REQUIRED, vm.state.value.note)
        assertNull(vm.state.value.error)
    }

    @Test fun `favorites source needs login, otherwise shows the liked songs`() = runTest {
        val songs = FakeSongs(20)
        val guest = vm(songs, favorites = songs.all.take(3), loggedIn = false)
        guest.setSource(PracticeSource.FAVORITES); advanceUntilIdle()
        assertTrue(guest.state.value.songs.isEmpty())
        assertTrue(guest.state.value.note!!.startsWith("로그인"))

        val member = vm(songs, favorites = songs.all.take(3))
        member.setSource(PracticeSource.FAVORITES); advanceUntilIdle()
        assertEquals(listOf("s1", "s2", "s3"), member.state.value.songs.map { it.id })
    }

    @Test fun `query searches the catalog after the debounce regardless of source`() = runTest {
        val songs = FakeSongs(120)
        val vm = vm(songs); advanceUntilIdle()
        vm.setSource(PracticeSource.FAVORITES)
        vm.setQuery("비 오는"); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(12, s.songs.size)
        assertTrue(s.songs.all { it.title.startsWith("비 오는") })
        assertEquals("비 오는" to 0, songs.requests.last())
    }
}
