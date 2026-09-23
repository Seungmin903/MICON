package com.micon.app.ui.onboarding

import androidx.lifecycle.viewModelScope
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceArtistPage
import com.micon.app.data.preferences.PreferenceDraft
import com.micon.app.data.preferences.PreferenceDraftStore
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.data.preferences.PreferenceSessionExpiredException
import com.micon.app.data.preferences.SavePreferencesRequest
import com.micon.app.data.preferences.UserPreferences
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceSurveyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<PreferenceSurveyViewModel>()
    private val genres = (1L..25L).map { PreferenceGenre(it, "장르 $it") }
    private val artists = (1L..55L).map { PreferenceArtist(it, "아티스트 $it") }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun model(repo: FakeRepository, drafts: MemoryDrafts = MemoryDrafts(), userId: Long = 7, editing: Boolean = false,
                      current: () -> Boolean = { true }, refresh: suspend () -> Unit = {}) =
        PreferenceSurveyViewModel(repo, drafts, userId, editing, current, refresh).also(models::add)

    @Test fun `genres precede artists and final save requires both selections`() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = model(repo)
        advanceUntilIdle()
        vm.next()
        vm.finish()
        assertEquals(0, vm.state.value.step)
        assertTrue(repo.saves.isEmpty())
        vm.toggleGenre(genres.first())
        vm.next()
        assertEquals(1, vm.state.value.step)
        assertFalse(vm.state.value.canFinish)
        vm.toggleArtist(artists.first())
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertEquals(listOf(1L), repo.saves.last().genreIds)
        assertEquals(listOf(1L), repo.saves.last().artistIds)
        assertTrue(repo.saves.last().onboardingCompleted)
    }

    @Test fun `selected artists survive pagination search and back navigation`() = runTest(dispatcher) {
        val vm = model(FakeRepository())
        advanceUntilIdle()
        vm.toggleGenre(genres.first())
        vm.next()
        vm.toggleArtist(artists.first())
        vm.next()
        vm.loadMoreArtists()
        advanceUntilIdle()
        assertEquals(55, vm.state.value.artists.size)
        vm.toggleArtist(artists[40])
        vm.setQuery("아티스트 50")
        advanceUntilIdle()
        assertEquals(listOf(50L), vm.state.value.artists.map { it.artistId })
        assertEquals(setOf(1L, 41L), vm.state.value.selectedArtists.keys)
        assertTrue(vm.back())
        vm.next()
        assertEquals(setOf(1L, 41L), vm.state.value.selectedArtists.keys)
    }

    @Test fun `step selection and saved draft keep both selections`() = runTest(dispatcher) {
        val store = MemoryDrafts()
        val vm = model(FakeRepository(), store)
        advanceUntilIdle()
        vm.toggleGenre(genres.first())
        vm.next()
        vm.toggleArtist(artists.first())
        vm.selectStep(0)
        assertEquals(0, vm.state.value.step)
        assertEquals(setOf(1L), vm.state.value.selectedArtists.keys)
        vm.selectStep(1)
        assertEquals(1, vm.state.value.step)
        assertEquals(setOf(1L), vm.state.value.selectedGenres.keys)
        assertTrue(vm.state.value.canFinish)
        assertEquals(1, store.read(7, false)!!.step)
        assertTrue(vm.back())
        assertEquals(0, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test fun `search is debounced and late responses cannot replace current results`() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<PreferenceArtistPage>()
        val repo = FakeRepository().apply {
            artistHandler = { query, _ ->
                if (query == "old") withContext(NonCancellable) { oldResponse.await() }
                else PreferenceArtistPage(listOf(artists[9]), totalPages = 1)
            }
        }
        val vm = model(repo)
        runCurrent()
        vm.setQuery("o")
        advanceTimeBy(100)
        vm.setQuery("old")
        advanceTimeBy(351)
        runCurrent()
        assertFalse(repo.searches.any { it.first == "o" })
        vm.setQuery("new")
        advanceTimeBy(351)
        runCurrent()
        oldResponse.complete(PreferenceArtistPage(listOf(artists.first()), totalPages = 1))
        advanceUntilIdle()
        assertEquals("new", vm.state.value.query)
        assertEquals(listOf(10L), vm.state.value.artists.map { it.artistId })
    }

    @Test fun `slow draft completes before final save and cannot overwrite final selection`() = runTest(dispatcher) {
        val draftResponse = CompletableDeferred<Unit>()
        val repo = FakeRepository().apply { saveHandler = { if (!it.onboardingCompleted) draftResponse.await(); saved(it) } }
        val vm = model(repo)
        runCurrent()
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        advanceTimeBy(601)
        runCurrent()
        assertEquals(1, repo.saves.size)
        assertFalse(repo.saves.single().onboardingCompleted)
        vm.toggleGenre(genres[1])
        vm.finish()
        runCurrent()
        assertTrue(vm.state.value.isSaving)
        assertEquals(1, repo.saves.size)
        draftResponse.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repo.saves.size)
        assertEquals(listOf(1L, 2L), repo.saves.last().genreIds)
        assertTrue(repo.saves.last().onboardingCompleted)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `repeated completion waits for one successful final save`() = runTest(dispatcher) {
        val response = CompletableDeferred<Unit>()
        val repo = FakeRepository().apply { saveHandler = { response.await(); saved(it) } }
        val vm = model(repo)
        runCurrent()
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        vm.finish()
        vm.finish()
        runCurrent()
        assertEquals(1, repo.saves.size)
        assertTrue(vm.state.value.isSaving)
        assertFalse(vm.state.value.saved)
        response.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        vm.finish()
        advanceUntilIdle()
        assertEquals(1, repo.saves.size)
    }

    @Test fun `final save failure preserves draft and retry alone completes onboarding`() = runTest(dispatcher) {
        val store = MemoryDrafts()
        var failing = true
        val repo = FakeRepository().apply { saveHandler = { if (failing) throw IOException(); saved(it) } }
        val vm = model(repo, store)
        runCurrent()
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        vm.finish()
        runCurrent()
        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.isSaving)
        assertNotNull(vm.state.value.saveError)
        assertNotNull(store.read(7, false))
        failing = false
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        assertNull(store.read(7, false))
    }

    @Test fun `offline draft restores across recreation and remains isolated by account`() = runTest(dispatcher) {
        val store = MemoryDrafts()
        val repo = FakeRepository().apply { saveHandler = { throw IOException() } }
        val first = model(repo, store)
        runCurrent()
        first.toggleGenre(genres[1])
        first.next()
        first.toggleArtist(artists[4])
        advanceUntilIdle()
        assertNotNull(first.state.value.draftError)
        first.viewModelScope.cancel()
        val restored = model(repo, store)
        val otherAccount = model(repo, store, userId = 8)
        advanceUntilIdle()
        assertEquals(setOf(2L), restored.state.value.selectedGenres.keys)
        assertEquals(setOf(5L), restored.state.value.selectedArtists.keys)
        assertEquals(0, restored.state.value.step)
        assertTrue(otherAccount.state.value.selectedGenres.isEmpty())
        assertTrue(otherAccount.state.value.selectedArtists.isEmpty())
    }

    @Test fun `interrupted initial survey returns to first step without losing selections`() = runTest(dispatcher) {
        val store = MemoryDrafts()
        val vm = model(FakeRepository(), store)
        advanceUntilIdle()
        vm.toggleGenre(genres.first())
        vm.next()
        vm.toggleArtist(artists.first())
        assertEquals(1, vm.state.value.step)

        vm.restartAtFirstStep()

        assertEquals(0, vm.state.value.step)
        assertEquals(0, store.read(7, false)?.step)
        assertEquals(setOf(1L), vm.state.value.selectedGenres.keys)
        assertEquals(setOf(1L), vm.state.value.selectedArtists.keys)
    }

    @Test fun `completed preference edits remain local until explicit valid save`() = runTest(dispatcher) {
        val store = MemoryDrafts()
        val repo = FakeRepository().apply { preferences = saved(SavePreferencesRequest(listOf(1), listOf(1), true)) }
        val vm = model(repo, store, editing = true)
        runCurrent()
        vm.toggleGenre(genres.first())
        advanceUntilIdle()
        assertTrue(repo.saves.isEmpty())
        assertFalse(vm.state.value.canFinish)
        assertTrue(store.read(7, true)!!.dirty)
        vm.toggleGenre(genres[1])
        vm.finish()
        advanceUntilIdle()
        assertEquals(1, repo.saves.size)
        assertTrue(repo.saves.single().onboardingCompleted)
    }

    @Test fun `recommendations regenerate once only after a successful settings save`() = runTest(dispatcher) {
        var refreshes = 0
        val repo = FakeRepository().apply { preferences = saved(SavePreferencesRequest(listOf(1), listOf(1), true)) }
        val vm = model(repo, editing = true, refresh = { refreshes++ })
        runCurrent()
        vm.toggleGenre(genres[1])
        advanceUntilIdle()
        assertEquals(0, refreshes) // local draft changes never start a job

        vm.toggleGenre(genres.first())
        vm.finish()
        vm.finish()
        advanceUntilIdle()

        assertTrue(vm.state.value.saved)
        assertEquals(1, refreshes)
    }

    @Test fun `catalogue errors can be retried without losing server choices`() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            failGenres = true
            preferences = saved(SavePreferencesRequest(listOf(2), listOf(4), false))
        }
        val vm = model(repo)
        advanceUntilIdle()
        assertNotNull(vm.state.value.loadError)
        repo.failGenres = false
        vm.reload()
        advanceUntilIdle()
        assertNull(vm.state.value.loadError)
        assertEquals(setOf(2L), vm.state.value.selectedGenres.keys)
        assertEquals(setOf(4L), vm.state.value.selectedArtists.keys)
    }

    @Test fun `artist page retry resumes failed page and does not duplicate earlier results`() = runTest(dispatcher) {
        var failing = true
        val repo = FakeRepository().apply {
            artistHandler = { _, page ->
                if (page == 1 && failing) throw IOException()
                PreferenceArtistPage(artists.drop(page * 30).take(30), page = page, totalPages = 2)
            }
        }
        val vm = model(repo)
        advanceUntilIdle()
        vm.loadMoreArtists()
        advanceUntilIdle()
        assertEquals(30, vm.state.value.artists.size)
        assertNotNull(vm.state.value.artistsError)
        failing = false
        vm.retryArtists()
        advanceUntilIdle()
        assertEquals(55, vm.state.value.artists.size)
        assertNull(vm.state.value.artistsError)
        assertFalse(vm.state.value.hasMoreArtists)
    }

    @Test fun `account switch prevents stale viewmodel from writing under the new account`() = runTest(dispatcher) {
        var current = true
        val repo = FakeRepository()
        val vm = model(repo, current = { current })
        runCurrent()
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        current = false
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.state.value.sessionExpired)
        assertFalse(vm.state.value.saved)
        assertTrue(repo.saves.isEmpty())
    }

    @Test fun `expired save redirects to login without success`() = runTest(dispatcher) {
        val repo = FakeRepository().apply { saveHandler = { throw PreferenceSessionExpiredException() } }
        val vm = model(repo)
        runCurrent()
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        vm.finish()
        advanceUntilIdle()
        assertTrue(vm.state.value.sessionExpired)
        assertFalse(vm.state.value.saved)
    }

    @Test fun `selection limits allow deselection but reject excess choices`() = runTest(dispatcher) {
        val vm = model(FakeRepository())
        runCurrent()
        genres.forEach(vm::toggleGenre)
        artists.forEach(vm::toggleArtist)
        assertEquals(20, vm.state.value.selectedGenres.size)
        assertEquals(50, vm.state.value.selectedArtists.size)
        vm.toggleGenre(genres.first())
        vm.toggleArtist(artists.first())
        assertEquals(19, vm.state.value.selectedGenres.size)
        assertEquals(49, vm.state.value.selectedArtists.size)
        advanceUntilIdle()
    }

    private inner class FakeRepository : PreferenceRepository {
        var preferences = UserPreferences()
        var failGenres = false
        val searches = mutableListOf<Pair<String, Int>>()
        val saves = mutableListOf<SavePreferencesRequest>()
        var artistHandler: (suspend (String, Int) -> PreferenceArtistPage)? = null
        var saveHandler: (suspend (SavePreferencesRequest) -> UserPreferences)? = null
        override suspend fun getGenres(): List<PreferenceGenre> {
            if (failGenres) throw IOException()
            return genres
        }
        override suspend fun getArtists(query: String, page: Int): PreferenceArtistPage {
            searches += query to page
            return artistHandler?.invoke(query, page) ?: artists.filter { it.name.contains(query) }.let {
                PreferenceArtistPage(it.drop(page * 30).take(30), page, 30, it.size.toLong(), (it.size + 29) / 30)
            }
        }
        override suspend fun getPreferences() = preferences
        override suspend fun savePreferences(request: SavePreferencesRequest): UserPreferences {
            saves += request
            return saveHandler?.invoke(request) ?: saved(request).also { preferences = it }
        }
        fun saved(request: SavePreferencesRequest) = UserPreferences(
            request.genreIds, request.artistIds, genres.filter { it.genreId in request.genreIds },
            artists.filter { it.artistId in request.artistIds }, request.onboardingCompleted,
        )
    }

    private class MemoryDrafts : PreferenceDraftStore {
        private val values = mutableMapOf<Pair<Long, Boolean>, PreferenceDraft>()
        override fun read(userId: Long, isEditing: Boolean) = values[userId to isEditing]
        override fun write(userId: Long, isEditing: Boolean, draft: PreferenceDraft) { values[userId to isEditing] = draft }
        override fun clear(userId: Long, isEditing: Boolean) { values.remove(userId to isEditing) }
    }
}
