package com.micon.app.ui.songlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SongListViewModel(private val repo: SongRepository, private val engine: EngineClient) : ViewModel() {
    data class State(
        val query: String = "",
        val analyzedOnly: Boolean = true,      // 분석 완료 곡만(연습 가능). 전체 곡을 보이려면 false.
        val songs: List<SongSummary> = emptyList(),
        val loading: Boolean = true,
        val engineOk: Boolean? = null,
        val engineStatus: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    init { refresh(); checkEngine() }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(250); refresh() }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            runCatching { repo.search(s.query, s.analyzedOnly, limit = 300) }
                .onSuccess { songs ->
                    UserStore.seedLikesIfEmpty(songs.map { it.id })
                    _state.update { it.copy(songs = songs, loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    fun checkEngine() {
        viewModelScope.launch {
            runCatching { engine.api.health() }
                .onSuccess { h -> _state.update { it.copy(engineOk = h.ok, engineStatus = "엔진 연결됨 · ${h.device ?: "?"}") } }
                .onFailure { e -> _state.update { it.copy(engineOk = false, engineStatus = engine.describe(e)) } }
        }
    }
}

/** Figma "노래 연습 - 곡 선택": 헤더, 검색, LP 아트 행(key 배지 · 찜) */
@Composable
fun SongListScreen(onSongClick: (String) -> Unit, onSettings: () -> Unit = {}) {
    val container = rememberAppContainer()
    val vm: SongListViewModel = viewModel(factory = viewModelFactory { initializer { SongListViewModel(container.songs, container.engine) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val liked by UserStore.liked.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        MiconSearchBar(state.query, vm::setQuery, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = GreyLight)
        when {
            state.error != null -> RetryBlock(state.error!!, vm::refresh)
            state.loading && state.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.title,
                        artist = song.artist,
                        imageUrl = song.imageUrl,
                        onClick = { onSongClick(song.id) },
                        keyBadge = UserStore.recommendedKey(song.id),
                        liked = song.id in liked,
                        onLike = { UserStore.toggleLike(song.id) },
                    )
                }
                if (state.songs.isEmpty()) item { Text("검색 결과가 없습니다.", Modifier.padding(24.dp), color = Grey) }
                item {
                    // 엔진 연결 실패 안내(개발용). Figma 에는 없는 요소라 목록 끝에 작게 둔다.
                    if (state.engineOk == false) Text(state.engineStatus, fontSize = 12.sp, color = Grey, modifier = Modifier.fillMaxWidth().padding(20.dp))
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
