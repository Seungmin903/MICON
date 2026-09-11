package com.micon.app.ui.songlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
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
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SongListViewModel(private val repo: SongRepository, private val recommend: RecommendRepository) : ViewModel() {
    data class State(
        val query: String = "",
        val songs: List<SongSummary> = emptyList(),
        val keys: Map<Long, Int?> = emptyMap(),   // songId → 추천 키 배지 (RecommendRepository.fit, 없으면 배지 없음)
        val loading: Boolean = true,
        val totalElements: Long = 0,              // 서버 전체 곡 수 (검색어 기준)
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    init { refresh() }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(250); refresh() }
    }

    fun refresh() {
    /**
     * 백엔드 `GET /songs` 는 제목·가수 검색과 페이지만 지원한다(언어·차트 분류·정렬 필터 없음).
     * 첫 페이지 100곡을 보인다. 무한 스크롤은 곡 수가 100을 넘을 때 붙인다.
     */
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            runCatching { repo.search(s.query, page = 0, size = PAGE_SIZE) }
                .onSuccess { page ->
                    UserStore.seedLikesIfEmpty(page.items.map { it.id })
                    _state.update { it.copy(songs = page.items, totalElements = page.totalElements, loading = false) }
                    val keys = page.items.associate { song -> song.id to runCatching { recommend.fit(song.id)?.recommendedKey }.getOrNull() }
                    _state.update { it.copy(keys = it.keys + keys) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    private companion object { const val PAGE_SIZE = 100 }
}

/** Figma "노래 연습 - 곡 선택": 헤더, 검색, 언어·분류 필터, LP 아트 행(분류 배지 · 찜) */
@Composable
fun SongListScreen(onSongClick: (String) -> Unit, onSettings: () -> Unit = {}) {
    val container = rememberAppContainer()
    val vm: SongListViewModel = viewModel(factory = viewModelFactory { initializer { SongListViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val liked by UserStore.liked.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        MiconSearchBar(state.query, vm::setQuery, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        // 1줄: 언어 (한국곡 / 팝송 / J-POP)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.languages.forEach { (label, code) -> FilterChip(label, selected = state.language == code) { vm.setLanguage(code) } }
        }
        Spacer(Modifier.height(6.dp))
        // 2줄: TJ 차트 분류 — 현재 언어 안에서 3곡 이상인 것만, 많은 순
        val tags = state.tagCounts.entries.filter { it.value >= 3 }.sortedByDescending { it.value }
        if (tags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("전체", selected = state.chartTag == null, muted = true) { vm.setChartTag(null) }
                tags.forEach { (tag, n) -> FilterChip("$tag $n", selected = state.chartTag == tag, muted = true) { vm.setChartTag(tag) } }
            }
        } else {
            MiconHeader(onSettings = onSettings)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "불러오는 중…" else "${state.totalElements}곡" + (if (state.songs.size < state.totalElements) " 중 ${state.songs.size}곡 표시" else ""),
                fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f),
        MiconSearchBar(state.query, vm::setQuery, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
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
                        keyBadge = state.keys[song.id],
                        liked = song.id in liked,
                        onLike = { UserStore.toggleLike(song.id) },
                    )
                }
                if (state.songs.isEmpty()) item { Text("검색 결과가 없습니다.", Modifier.padding(24.dp), color = Grey) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
