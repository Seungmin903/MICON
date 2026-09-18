package com.micon.app.ui.songdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.*
import com.micon.app.data.backend.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

data class SongDetailState(
    val loading: Boolean = true,
    val detail: SongDetail? = null,
    val error: String? = null,
    val fit: SongFit? = null,
    val fitLoading: Boolean = true,
    val fitError: String? = null,
    val notes: List<KaraokeNote> = emptyList(),
    val sections: List<PracticeSection> = emptyList(),
    val assetsLoading: Boolean = true,
    val assetsError: String? = null,
    val selectedKey: Int = 0,
    val mediaKey: Int? = null,
    val keyLoading: Boolean = false,
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val playbackError: String? = null,
) {
    val playable get() = mediaKey == selectedKey && !keyLoading && !assetsLoading
    val practiceEnabled get() = playable && detail?.practiceReady == true && notes.isNotEmpty()
}

internal class SongDetailViewModel(
    private val repo: SongRepository, private val recommend: RecommendRepository, private val songId: String,
    private val playerFactory: () -> SongPreviewPlayer,
    profile: StateFlow<UserStore.VoiceProfile?> = UserStore.profile,
) : ViewModel() {
    constructor(context: Context, repo: SongRepository, http: OkHttpClient, recommend: RecommendRepository, songId: String) :
        this(repo, recommend, songId, { Media3SongPreview(context, http) })

    private val _state = MutableStateFlow(SongDetailState())
    val state = _state.asStateFlow()
    private var player: SongPreviewPlayer? = null
    private var active = false
    private var mediaUrl: String? = null
    private var cachedAssets: PracticeAssets? = null
    private var loadJob: Job? = null
    private var keyJob: Job? = null
    private var fitJob: Job? = null
    private var progressJob: Job? = null

    init {
        load()
        viewModelScope.launch { profile.collectLatest { refreshFit() } }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.detail == null, error = null) }
            try {
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없어요.")
                ensureActive()
                check(detail.summary.id == songId)
                _state.update { it.copy(loading = false, detail = mergeAssets(detail),
                    durationSec = maxOf(it.durationSec, (detail.summary.durationMs ?: 0) / 1000.0)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = "곡 정보를 불러오지 못했어요.") } }
        }
        loadAssets(_state.value.selectedKey, initial = _state.value.notes.isEmpty())
    }

    private fun mergeAssets(detail: SongDetail): SongDetail {
        val assets = cachedAssets ?: return detail
        val analysis = detail.analysis ?: SongAnalysis(null, null, null, null, null)
        return detail.copy(analysis = analysis.copy(keyName = analysis.keyName ?: assets.keyName,
            tempoBpm = analysis.tempoBpm ?: assets.tempoBpm, timeSignature = analysis.timeSignature ?: assets.timeSignature,
            noteCount = _state.value.notes.size.takeIf { it > 0 } ?: analysis.noteCount))
    }

    fun refreshFit() {
        fitJob?.cancel()
        fitJob = viewModelScope.launch {
            _state.update { it.copy(fitLoading = true, fit = null, fitError = null) }
            try {
                val fit = recommend.fit(songId)
                ensureActive()
                check(fit == null || fit.songId == songId)
                _state.update { it.copy(fit = fit, fitLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(fitLoading = false, fitError = "맞춤 분석을 불러오지 못했어요.") } }
        }
    }

    fun selectKey(key: Int) {
        if (key !in -6..5 || key == _state.value.selectedKey) return
        loadAssets(key, initial = false)
    }
    fun retryAssets() = loadAssets(_state.value.selectedKey, initial = _state.value.notes.isEmpty())

    private fun loadAssets(key: Int, initial: Boolean) {
        keyJob?.cancel()
        releasePreview()
        mediaUrl = null
        _state.update { it.copy(selectedKey = key, mediaKey = null, assetsError = null, playbackError = null,
            assetsLoading = initial, keyLoading = !initial) }
        keyJob = viewModelScope.launch {
            try {
                val assets = repo.practiceAssets(songId, key)
                ensureActive()
                check(assets.songId == songId && assets.selectedKey == key)
                // Original-key notes: shift only in the display/scorer, never in this cache.
                val notes = assets.targetNotes.filter { it.sourceStart.isFinite() && it.sourceEnd.isFinite() &&
                    it.sourceStart >= 0 && it.sourceEnd > it.sourceStart && it.midi in 0..127 }
                cachedAssets = assets
                mediaUrl = assets.instrumental.playbackUrl?.takeIf { it.isNotBlank() }
                _state.update { it.copy(notes = notes, sections = assets.sections,
                    assetsLoading = false, keyLoading = false, mediaKey = if (mediaUrl != null) key else null,
                    durationSec = maxOf(it.durationSec, notes.maxOfOrNull { n -> n.sourceEnd } ?: 0.0),
                    assetsError = if (mediaUrl == null) "반주가 아직 준비되지 않았어요." else null) }
                _state.update { it.copy(detail = it.detail?.let(::mergeAssets)) }
                preparePreview() // A late response on a hidden screen must not recreate a player.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(assetsLoading = false, keyLoading = false, mediaKey = null, assetsError = when (e) {
                    is UnsupportedPracticeKeyException -> "${songKeyLabel(key)} 키 반주가 아직 준비되지 않았어요. 원키로 돌아가서 연습할 수 있어요."
                    is PracticeAssetsNotReadyException -> "이 곡의 반주·음표 데이터가 아직 준비되지 않았어요."
                    else -> "연습 데이터를 불러오지 못했어요. 다시 시도해 주세요."
                }) }
            }
        }
    }

    fun enterScreen() {
        if (active) return
        active = true
        // Refresh expiring signed URLs on returning to a saved tab. Never resume automatically.
        if (!_state.value.assetsLoading && !_state.value.keyLoading && mediaUrl != null) retryAssets()
    }

    fun leaveScreen() {
        active = false
        releasePreview()
    }

    private fun preparePreview(): SongPreviewPlayer? {
        if (!active || !_state.value.playable) return null
        player?.let { return it }
        val url = mediaUrl ?: return null
        val next = playerFactory()
        player = next
        next.onError = { if (player === next) {
            next.pause()
            _state.update { it.copy(playing = false, playbackError = "반주를 재생하지 못했어요. 다시 불러와 주세요.") }
        } }
        next.onEnded = { if (player === next) { next.pause(); _state.update { it.copy(playing = false) } } }
        try {
            next.load(url)
            next.seekTo(_state.value.positionSec)
        } catch (e: Exception) {
            releasePreview()
            _state.update { it.copy(playbackError = "반주를 재생하지 못했어요. 다시 불러와 주세요.") }
            return null
        }
        progressJob = viewModelScope.launch {
            while (isActive && player === next && active) {
                if (next.isReady || next.isPlaying) _state.update { it.copy(positionSec = next.positionSec,
                    durationSec = maxOf(it.durationSec, next.durationSec ?: 0.0), playing = next.playRequested) }
                delay(200)
            }
        }
        return next
    }

    private fun releasePreview() {
        progressJob?.cancel()
        progressJob = null
        val old = player
        player = null
        if (old != null) {
            if (old.isReady || old.isPlaying) _state.update { it.copy(positionSec = old.positionSec) }
            old.onError = null
            old.onEnded = null
            old.pause() // Cancels playWhenReady even while buffering.
            old.release()
        }
        _state.update { it.copy(playing = false) }
    }

    fun toggle() {
        val current = preparePreview() ?: return
        if (current.playRequested) pause() else { current.play(); _state.update { it.copy(playing = true) } }
    }

    fun seekTo(seconds: Double) {
        if (!seconds.isFinite()) return
        val current = preparePreview() ?: return
        val target = seconds.coerceIn(0.0, _state.value.durationSec.coerceAtLeast(0.0))
        current.seekTo(target)
        _state.update { it.copy(positionSec = target) }
    }

    fun listenAt(seconds: Double) {
        if (!seconds.isFinite()) return
        val current = preparePreview() ?: return
        seekTo(seconds)
        current.play()
        _state.update { it.copy(playing = true) }
    }

    fun pause() { player?.pause(); _state.update { it.copy(playing = false) } }
    override fun onCleared() { leaveScreen() }
}
