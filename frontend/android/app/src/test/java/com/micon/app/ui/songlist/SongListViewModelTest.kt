package com.micon.app.ui.songlist

import com.micon.app.data.PracticeAssets
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelves
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.preferences.PreferenceArtistPage
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.data.preferences.SavePreferencesRequest
import com.micon.app.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 곡 선택 목록의 무한 스크롤: 서버 페이지(최대 100)를 이어 받고, 검색어가 바뀌면 진행 중인 요청을 버린다. */
@OptIn(ExperimentalCoroutinesApi::class)
class SongListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** [total] 곡 카탈로그. 검색어는 제목 포함 여부로 걸러 서버처럼 페이지를 만든다. */
    private class FakeSongs(total: Int) : SongRepository {
        val all = (1..total).map { SongSummary("s$it", if (it % 10 == 0) "비 오는 날 $it" else "곡 $it", "가수 ${it % 7}", null, 200_000) }
        val requests = mutableListOf<Pair<String, Int>>()
        val genreRequests = mutableListOf<Long?>()
        var failNext = false
        override suspend fun search(query: String, page: Int, size: Int): SongPage {
            requests += query to page
            return page(query, page, size)
        }
        override suspend fun search(query: String, page: Int, size: Int, genreId: Long?): SongPage {
            requests += query to page
            genreRequests += genreId
            return page(query, page, size)
        }
        private fun page(query: String, page: Int, size: Int): SongPage {
            if (failNext) { failNext = false; throw IllegalStateException("network") }
            val hit = if (query.isBlank()) all else all.filter { it.title.contains(query) }
            val items = hit.drop(page * size).take(size)
            return SongPage(items, page, size, hit.size.toLong(), (hit.size + size - 1) / size)
        }
        override suspend fun detail(songId: String): SongDetail? = null
        override suspend fun practiceAssets(songId: String, selectedKey: Int): PracticeAssets = throw UnsupportedOperationException()
    }

    private val preferences = object : PreferenceRepository {
        override suspend fun getGenres() = listOf(PreferenceGenre(20, "K-POP"), PreferenceGenre(21, "발라드"))
        override suspend fun getArtists(query: String, page: Int) = PreferenceArtistPage()
        override suspend fun getPreferences() = UserPreferences()
        override suspend fun savePreferences(request: SavePreferencesRequest) = UserPreferences()
    }

    private val recommend = object : RecommendRepository {
        override suspend fun fit(songId: String): SongFit? = null
        override suspend fun shelves(limit: Int, reshuffle: Int) = RecommendShelves(false, emptyList())
    }

    @Test fun `first page then load more until the catalog is exhausted`() = runTest {
        val songs = FakeSongs(120)
        val vm = SongListViewModel(songs, recommend, preferences); advanceUntilIdle()
        assertEquals(50, vm.state.value.songs.size)                    // 페이지 크기 50
        assertEquals(120L, vm.state.value.totalElements)
        assertTrue(vm.state.value.hasMore)

        vm.loadMore(); advanceUntilIdle()
        assertEquals(100, vm.state.value.songs.size)
        assertEquals(1, vm.state.value.page)
        assertTrue(vm.state.value.hasMore)

        vm.loadMore(); advanceUntilIdle()
        assertEquals(120, vm.state.value.songs.size)
        assertFalse(vm.state.value.hasMore)
        assertEquals(120, vm.state.value.songs.map { it.id }.toSet().size)  // 중복 없음

        vm.loadMore(); advanceUntilIdle()                                // 더 없으면 서버를 부르지 않는다
        assertEquals(listOf("" to 0, "" to 1, "" to 2), songs.requests)
    }

    @Test fun `load more failure keeps the list and can be retried`() = runTest {
        val songs = FakeSongs(80)
        val vm = SongListViewModel(songs, recommend, preferences); advanceUntilIdle()
        songs.failNext = true
        vm.loadMore(); advanceUntilIdle()
        assertEquals(50, vm.state.value.songs.size)
        assertFalse(vm.state.value.loadingMore)
        assertTrue(vm.state.value.moreError != null)
        assertNull(vm.state.value.error)                                 // 첫 페이지 오류와 구분

        vm.loadMore(); advanceUntilIdle()                                // 다시 시도
        assertEquals(80, vm.state.value.songs.size)
        assertNull(vm.state.value.moreError)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun `query change resets paging and drops the in-flight page`() = runTest {
        val songs = FakeSongs(120)
        val vm = SongListViewModel(songs, recommend, preferences); advanceUntilIdle()
        vm.loadMore()                                                    // 아직 완료 전
        vm.setQuery("비 오는"); advanceUntilIdle()                        // 250 ms 디바운스 뒤 새 검색
        val s = vm.state.value
        assertEquals(12, s.songs.size)                                   // "비 오는 날 10·20·…·120"
        assertTrue(s.songs.all { it.title.startsWith("비 오는") })
        assertEquals(0, s.page)
        assertFalse(s.hasMore)
        assertFalse(s.loadingMore)
    }

    @Test fun `genre selection resets to the first page and sends its id`() = runTest {
        val songs = FakeSongs(120)
        val vm = SongListViewModel(songs, recommend, preferences); advanceUntilIdle()

        vm.setGenre(21); advanceUntilIdle()

        assertEquals(21L, vm.state.value.selectedGenreId)
        assertEquals(0, vm.state.value.page)
        assertEquals(21L, songs.genreRequests.last())
    }

    @Test fun `rapid genre changes request only the final genre after debounce`() = runTest {
        val songs = FakeSongs(120)
        val vm = SongListViewModel(songs, recommend, preferences); advanceUntilIdle()
        vm.setGenre(20)
        vm.setGenre(21)
        vm.setGenre(null)
        advanceUntilIdle()

        assertEquals(listOf<Long?>(null, null), songs.genreRequests)
        assertEquals(null, vm.state.value.selectedGenreId)
    }
}
