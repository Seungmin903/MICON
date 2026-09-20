package com.micon.app.ui.songlist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.atoms.MiconSearchField
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 연습 탭 — 프로토타입 `practice`: 검색 + 세 갈래(추천곡 · 찜한 곡 · 최근 연습). 곡 행은 찜·기록 탭과 같은 [SongRow] */
enum class PracticeSource(val label: String) { RECOMMEND("추천곡"), FAVORITES("찜한 곡"), RECENT("최근 연습") }

class SongListViewModel(
    private val repo: SongRepository,
    private val recommend: RecommendRepository,
    private val favorites: FavoriteRepository,
    private val practices: PracticeRepository,
    private val isLoggedIn: () -> Boolean,
) : ViewModel() {
    data class State(
        val query: String = "",
        val source: PracticeSource = PracticeSource.RECOMMEND,
        val songs: List<SongSummary> = emptyList(),
        /** songId → 추천 키(반음). null = 정보 없음 */
        val keys: Map<String, Int?> = emptyMap(),
        val loading: Boolean = true,
        val error: String? = null,
        /** 갈래가 비었을 때 안내 */
        val note: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        job?.cancel()
        job = viewModelScope.launch { delay(250); load() }
    }

    fun setSource(s: PracticeSource) {
        if (_state.value.source == s) return
        _state.update { it.copy(source = s) }
        refresh()
    }

    fun refresh() { job?.cancel(); job = viewModelScope.launch { load() } }

    private suspend fun load() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null, note = null) }
        try {
            val (songs, note) = when {
                s.query.isNotBlank() -> repo.search(s.query.trim(), 0, 50).items to null
                s.source == PracticeSource.RECOMMEND -> {
                    val shelves = recommend.shelves(limit = 10)
                    val items = shelves.shelves.flatMap { it.items }.distinctBy { it.song.id }.map { it.song }
                    // 추천 근거가 없으면 전체 곡으로 대신
                    if (items.isEmpty()) repo.search("", 0, 50).items to shelves.note else items to null
                }
                s.source == PracticeSource.FAVORITES ->
                    if (!isLoggedIn()) emptyList<SongSummary>() to "로그인하면 찜한 곡을 볼 수 있어요." else favorites.refresh() to null
                else -> {
                    if (!isLoggedIn()) emptyList<SongSummary>() to "로그인하면 최근 연습한 곡이 여기 모여요."
                    else {
                        val ids = practices.history(0, 50).items.map { it.songId }.distinct().take(20)
                        ids.mapNotNull { id -> runCatching { repo.detail(id)?.summary }.getOrNull() } to (if (ids.isEmpty()) "아직 연습한 곡이 없어요." else null)
                    }
                }
            }
            _state.update { it.copy(songs = songs, loading = false, note = note) }
            val missing = songs.filter { it.id !in _state.value.keys }
            if (missing.isNotEmpty()) {
                val keys = missing.associate { song -> song.id to runCatching { recommend.fit(song.id)?.recommendedKey }.getOrNull() }
                _state.update { it.copy(keys = it.keys + keys) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(loading = false, error = describeBackendError(e, "곡을 불러오지 못했어요.")) } }
    }
}

/** 추천 키 → 배지 문구·색. 원래 키 = 편안, 한 키 = 주의, 두 키 이상 = 도전 */
fun keyBadge(key: Int?): Pair<String, MiconChipTone>? = when {
    key == null -> null
    key == 0 -> "원래 키" to MiconChipTone.Success
    key == -1 -> "한 키 ↓" to MiconChipTone.Warning
    key == 1 -> "한 키 ↑" to MiconChipTone.Warning
    key < 0 -> "${-key}키 ↓" to MiconChipTone.Challenge
    else -> "${key}키 ↑" to MiconChipTone.Challenge
}

@Composable
fun SongListScreen(
    onSongClick: (String) -> Unit,
    onSettings: () -> Unit = {},
    onLogin: (() -> Unit)? = null,
    backTitle: String? = null,
    onBack: () -> Unit = {},
    subtitle: String? = null,
) {
    val container = rememberAppContainer()
    val vm: SongListViewModel = viewModel(factory = viewModelFactory {
        initializer { SongListViewModel(container.songs, container.recommend, container.favorites, container.practices, isLoggedIn = { TokenManager.isLoggedIn() }) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume { if (!vm.state.value.loading) vm.refresh() }

    MiconScreen(
        appBar = {
            if (backTitle != null) MiconAppBar(title = backTitle, onBack = onBack)
            else MiconAppBar(title = "연습", actions = {
                if (onLogin != null) MiconButton("로그인", onLogin, kind = MiconButtonKind.Text)
                IconButton(onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "설정") }
            })
        },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
    ) {
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MiconSearchField(state.query, vm::setQuery, "노래나 가수를 검색해 보세요")
        if (state.query.isBlank()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
                PracticeSource.entries.forEach { s -> MiconFilterChip(s.label, selected = state.source == s, onClick = { vm.setSource(s) }, emphasis = true) }
            }
        }
        when {
            state.error != null -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MiconButton("다시 불러오기", vm::refresh, kind = MiconButtonKind.Text)
            }
            state.loading && state.songs.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.songs.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.note ?: "검색 결과가 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onLogin != null && state.note?.startsWith("로그인") == true) MiconButton("로그인", onLogin, kind = MiconButtonKind.Text)
            }
            else -> {
                state.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.songs.forEach { song ->
                        val badge = keyBadge(state.keys[song.id])
                        SongRow(title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                            trailing = badge?.let { (t, tone) -> { MiconBadge(t, tone = tone) } })
                    }
                }
            }
        }
    }
}
