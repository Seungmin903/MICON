package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceDraft
import com.micon.app.data.preferences.PreferenceDraftStore
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.data.preferences.PreferenceRequestException
import com.micon.app.data.preferences.PreferenceSessionExpiredException
import com.micon.app.data.preferences.SavePreferencesRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PreferenceSurveyState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val step: Int = 0,
    val genres: List<PreferenceGenre> = emptyList(),
    val artists: List<PreferenceArtist> = emptyList(),
    val selectedGenres: Map<Long, PreferenceGenre> = emptyMap(),
    val selectedArtists: Map<Long, PreferenceArtist> = emptyMap(),
    val query: String = "",
    val artistsLoading: Boolean = false,
    val artistsError: String? = null,
    val nextPage: Int = 0,
    val hasMoreArtists: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val draftSaving: Boolean = false,
    val draftError: String? = null,
    val draftPending: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    val sessionExpired: Boolean = false,
) {
    val canContinue get() = !loading && loadError == null && !isSaving && !sessionExpired && !saved && selectedGenres.isNotEmpty()
    val canFinish get() = canContinue && selectedArtists.isNotEmpty()
}

/** Selection is independent of search pages. Draft writes and final writes share one queue. */
@OptIn(FlowPreview::class)
class PreferenceSurveyViewModel(
    private val repository: PreferenceRepository,
    private val drafts: PreferenceDraftStore,
    private val userId: Long,
    private val isEditing: Boolean = false,
    private val isCurrentUser: () -> Boolean = { true },
) : ViewModel() {
    private val mutableState = MutableStateFlow(PreferenceSurveyState())
    val state = mutableState.asStateFlow()
    private val saveMutex = Mutex()
    private val draftChanges = Channel<Unit>(Channel.CONFLATED)
    private var artistsJob: Job? = null
    private var loadJob: Job? = null
    private var searchGeneration = 0
    private var revision = 0L
    private var dirty = false
    private var alreadyCompleted = false

    init {
        // Do not cancel an in-flight PUT when selection changes: its server commit may still run.
        viewModelScope.launch {
            draftChanges.receiveAsFlow().debounce(600).collect { saveDraft() }
        }
        reload()
    }

    fun reload() {
        if (state.value.isSaving || !checkSession()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, loadError = null) }
            try {
                val (genres, preferences) = coroutineScope {
                    val catalogue = async { repository.getGenres() }
                    val saved = async { repository.getPreferences() }
                    catalogue.await() to saved.await()
                }
                if (!checkSession()) return@launch
                alreadyCompleted = preferences.onboardingCompleted
                val draft = drafts.read(userId, isEditing)
                val local = draft?.takeIf { it.dirty }
                val selectedGenres = (local?.genres ?: genres.filter { it.genreId in preferences.genreIds })
                    .filter { item -> genres.any { it.genreId == item.genreId } }
                    .take(MAX_GENRES).associateBy { it.genreId }
                val selectedArtists = (local?.artists ?: preferences.artists.filter { it.artistId in preferences.artistIds })
                    .take(MAX_ARTISTS).associateBy { it.artistId }
                dirty = local != null
                mutableState.update {
                    it.copy(
                        loading = false, genres = genres, selectedGenres = selectedGenres,
                        selectedArtists = selectedArtists, draftPending = dirty,
                        step = if (selectedGenres.isEmpty()) 0 else (draft?.step ?: 0).coerceIn(0, 1),
                        saveError = null, draftError = null,
                    )
                }
                searchArtists(state.value.query, immediate = true)
                if (dirty) draftChanges.trySend(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (handleSessionError(error)) return@launch
                mutableState.update { it.copy(loading = false, loadError = describe(error)) }
            }
        }
    }

    fun toggleGenre(genre: PreferenceGenre) {
        if (!canMutate() || state.value.genres.none { it.genreId == genre.genreId }) return
        val selection = state.value.selectedGenres
        if (genre.genreId !in selection && selection.size >= MAX_GENRES) {
            mutableState.update { it.copy(message = "장르는 최대 ${MAX_GENRES}개까지 선택할 수 있어요.") }
            return
        }
        mutableState.update {
            it.copy(selectedGenres = if (genre.genreId in selection) selection - genre.genreId else selection + (genre.genreId to genre))
        }
        selectionChanged()
    }

    fun toggleArtist(artist: PreferenceArtist) {
        if (!canMutate()) return
        val selection = state.value.selectedArtists
        if (artist.artistId !in selection && selection.size >= MAX_ARTISTS) {
            mutableState.update { it.copy(message = "아티스트는 최대 ${MAX_ARTISTS}명까지 선택할 수 있어요.") }
            return
        }
        mutableState.update {
            it.copy(selectedArtists = if (artist.artistId in selection) selection - artist.artistId else selection + (artist.artistId to artist))
        }
        selectionChanged()
    }

    fun next() {
        if (state.value.canContinue && checkSession()) {
            mutableState.update { it.copy(step = 1, message = null) }
            persistDraft()
        }
    }

    /** Returns true when back was consumed by the artist → genre step. */
    fun back(): Boolean {
        if (state.value.isSaving) return true
        if (state.value.step == 0) return false
        mutableState.update { it.copy(step = 0, message = null) }
        persistDraft()
        return true
    }

    fun setQuery(query: String) {
        if (query == state.value.query || !canMutate()) return
        mutableState.update { it.copy(query = query.take(100)) }
        searchArtists(state.value.query, immediate = false)
    }

    fun loadMoreArtists() {
        val current = state.value
        if (current.artistsLoading || !current.hasMoreArtists || !canMutate()) return
        fetchArtists(current.query, current.nextPage, searchGeneration)
    }

    fun retryArtists() {
        if (state.value.artistsLoading || !canMutate()) return
        fetchArtists(state.value.query, state.value.nextPage, searchGeneration)
    }

    fun retryDraft() {
        if (checkSession()) draftChanges.trySend(Unit)
    }

    fun finish() {
        if (!state.value.canFinish || !checkSession() || state.value.saved) return
        mutableState.update { it.copy(isSaving = true, saveError = null, message = null) }
        viewModelScope.launch {
            saveMutex.withLock {
                if (!checkSession()) return@withLock
                try {
                    val snapshot = state.value
                    val saved = repository.savePreferences(snapshot.request(completed = true))
                    if (!checkSession()) return@withLock
                    if (!saved.onboardingCompleted) throw PreferenceRequestException(null, "취향 저장이 완료되지 않았어요. 다시 시도해주세요.")
                    dirty = false
                    alreadyCompleted = true
                    drafts.clear(userId, isEditing)
                    mutableState.update { it.copy(isSaving = false, saved = true, draftPending = false, draftError = null) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (handleSessionError(error)) return@withLock
                    mutableState.update { it.copy(isSaving = false, saveError = describe(error)) }
                }
            }
        }
    }

    private fun selectionChanged() {
        revision++
        dirty = true
        mutableState.update { it.copy(message = null, saveError = null, draftError = null, draftPending = true) }
        persistDraft()
        draftChanges.trySend(Unit)
    }

    private fun persistDraft() {
        if (userId <= 0 || !isCurrentUser()) return
        val current = state.value
        drafts.write(userId, isEditing, PreferenceDraft(current.selectedGenres.values.toList(), current.selectedArtists.values.toList(), current.step, dirty))
    }

    private suspend fun saveDraft() = saveMutex.withLock {
        // Editing a completed profile is explicit: intermediate deselections stay only in its local draft.
        if (!dirty || isEditing || alreadyCompleted || state.value.isSaving || state.value.saved || !checkSession()) return@withLock
        val sentRevision = revision
        val snapshot = state.value
        mutableState.update { it.copy(draftSaving = true, draftError = null) }
        try {
            repository.savePreferences(snapshot.request(completed = false))
            if (!checkSession()) return@withLock
            if (revision == sentRevision) {
                dirty = false
                persistDraft()
            }
            mutableState.update { it.copy(draftSaving = false, draftPending = dirty) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (handleSessionError(error)) return@withLock
            mutableState.update { it.copy(draftSaving = false, draftError = "이 기기에 임시 저장했어요. 서버 동기화를 다시 시도해주세요.") }
        }
    }

    private fun searchArtists(query: String, immediate: Boolean) {
        searchGeneration++
        artistsJob?.cancel()
        val generation = searchGeneration
        mutableState.update { it.copy(artists = emptyList(), nextPage = 0, hasMoreArtists = false, artistsLoading = true, artistsError = null) }
        artistsJob = viewModelScope.launch {
            if (!immediate) delay(350)
            requestArtists(query, page = 0, generation = generation)
        }
    }

    private fun fetchArtists(query: String, page: Int, generation: Int) {
        mutableState.update { it.copy(artistsLoading = true, artistsError = null) }
        artistsJob = viewModelScope.launch { requestArtists(query, page, generation) }
    }

    private suspend fun requestArtists(query: String, page: Int, generation: Int) {
        if (!checkSession()) return
        try {
            val response = repository.getArtists(query.trim(), page)
            if (generation != searchGeneration || !checkSession()) return
            mutableState.update {
                it.copy(
                    artists = (if (page == 0) response.content else it.artists + response.content).distinctBy { artist -> artist.artistId },
                    artistsLoading = false, artistsError = null, nextPage = page + 1,
                    hasMoreArtists = page + 1 < response.totalPages,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation != searchGeneration || handleSessionError(error)) return
            mutableState.update { it.copy(artistsLoading = false, artistsError = describe(error)) }
        }
    }

    private fun PreferenceSurveyState.request(completed: Boolean) = SavePreferencesRequest(
        genreIds = selectedGenres.keys.toList(), artistIds = selectedArtists.keys.toList(), onboardingCompleted = completed,
    )

    private fun canMutate() = !state.value.loading && state.value.loadError == null && !state.value.isSaving && !state.value.saved && checkSession()

    private fun checkSession(): Boolean {
        if (userId > 0 && isCurrentUser() && !state.value.sessionExpired) return true
        mutableState.update { it.copy(sessionExpired = true, loading = false, isSaving = false, draftSaving = false, artistsLoading = false) }
        return false
    }

    private fun handleSessionError(error: Exception): Boolean {
        if (error is PreferenceSessionExpiredException || !isCurrentUser()) {
            mutableState.update { it.copy(sessionExpired = true, loading = false, isSaving = false, draftSaving = false, artistsLoading = false) }
            return true
        }
        return false
    }

    private fun describe(error: Exception) = if (error is PreferenceRequestException) {
        error.message ?: "요청을 처리하지 못했어요. 다시 시도해주세요."
    } else {
        "연결이 원활하지 않아요. 인터넷 연결을 확인하고 다시 시도해주세요."
    }

    companion object {
        const val MAX_GENRES = 20
        const val MAX_ARTISTS = 50
    }
}
