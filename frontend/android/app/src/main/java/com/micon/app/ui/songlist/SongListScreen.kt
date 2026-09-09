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
import com.micon.app.ui.theme.Red
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
        val counts: SongRepository.Counts? = null,   // 연습 가능 곡 수 · 가사 싱크 곡 수
        val language: String? = null,                 // null=전체, kor=한국곡, eng=팝송, jpn=J-POP
        val chartTag: String? = null,                 // TJ 차트 분류 (발라드·댄스…)
        val sort: SongRepository.Sort = SongRepository.Sort.ARTIST,
        val tagCounts: Map<String, Int> = emptyMap(), // 현재 언어 안의 분류별 곡 수 (칩 라벨·순서)
        val error: String? = null,
    )

    /** 언어 칩: 라벨 ↔ primary_language */
    val languages: List<Pair<String, String?>> = listOf("전체" to null, "한국곡" to "kor", "팝송" to "eng", "J-POP" to "jpn")

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    init { refresh(); checkEngine(); loadCounts(); loadTagCounts() }

    private fun loadCounts() {
        viewModelScope.launch { runCatching { repo.counts() }.onSuccess { c -> _state.update { it.copy(counts = c) } } }
    }

    private fun loadTagCounts() {
        viewModelScope.launch {
            runCatching { repo.chartTagCounts(_state.value.language) }.onSuccess { m -> _state.update { it.copy(tagCounts = m) } }
        }
    }

    fun setLanguage(language: String?) {
        _state.update { it.copy(language = language, chartTag = null) }
        loadTagCounts(); refresh()
    }

    fun setChartTag(tag: String?) {
        _state.update { it.copy(chartTag = if (it.chartTag == tag) null else tag) }
        refresh()
    }

    fun cycleSort() {
        _state.update { it.copy(sort = SongRepository.Sort.entries[(it.sort.ordinal + 1) % SongRepository.Sort.entries.size]) }
        refresh()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(250); refresh() }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            runCatching { repo.search(s.query, s.analyzedOnly, limit = 300, language = s.language, chartTag = s.chartTag, sort = s.sort) }
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

/** 필터 칩. [muted] 는 2줄(분류)용 회색 톤. */
@Composable
private fun FilterChip(label: String, selected: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50),
        color = if (selected) Purple else if (muted) GreyLight.copy(alpha = 0.6f) else GreyLight,
    ) {
        Text(
            label, fontSize = if (muted) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else Grey,
            modifier = Modifier.padding(horizontal = if (muted) 10.dp else 13.dp, vertical = if (muted) 5.dp else 7.dp),
        )
    }
}

/** Figma "노래 연습 - 곡 선택": 헤더, 검색, 언어·분류 필터, LP 아트 행(분류 배지 · 찜) */
@Composable
fun SongListScreen(onSongClick: (String) -> Unit, onSettings: () -> Unit = {}) {
    val container = rememberAppContainer()
    val vm: SongListViewModel = viewModel(factory = viewModelFactory { initializer { SongListViewModel(container.songs, container.engine) } })
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
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "불러오는 중…" else "${state.songs.size}곡" + (state.counts?.let { " · 연습 가능 ${it.analyzed}곡" } ?: ""),
                fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f),
            )
            Text(
                "정렬: " + when (state.sort) { SongRepository.Sort.ARTIST -> "가수순"; SongRepository.Sort.TITLE -> "제목순"; SongRepository.Sort.NEWEST -> "최신순" } + " ▾",
                fontSize = 12.sp, color = Purple, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = vm::cycleSort).padding(4.dp),
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
                        keyBadge = UserStore.recommendedKey(song.id),
                        liked = song.id in liked,
                        onLike = { UserStore.toggleLike(song.id) },
                        // 분류(발라드·댄스…)를 우선 보이고, 가사 싱크가 없는 곡만 그 상태를 표시
                        subBadge = when {
                            !song.lyricsSynced && song.hasLyrics -> "가사만"
                            !song.hasLyrics -> "반주만"
                            else -> song.chartTag
                        },
                        subBadgeColor = when {
                            !song.lyricsSynced && song.hasLyrics -> Grey
                            !song.hasLyrics -> Red
                            else -> Purple
                        },
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
