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
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.micon.app.core.auth.TokenManager
import com.micon.app.ui.common.describeBackendError
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.ui.common.BackHeader
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
        val keys: Map<String, Int?> = emptyMap(),   // songId → 추천 키 배지 (RecommendRepository.fit, 없으면 배지 없음)
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

    /**
     * 백엔드 `GET /songs` 는 제목·가수 검색과 페이지만 지원한다(언어·차트 분류·정렬 필터 없음).
     * 첫 페이지 100곡을 보인다. 무한 스크롤은 곡 수가 100을 넘을 때 붙인다.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            runCatching { repo.search(s.query, page = 0, size = PAGE_SIZE) }
                .onSuccess { page ->
                    _state.update { it.copy(songs = page.items, totalElements = page.totalElements, loading = false) }
                    val keys = page.items.associate { song -> song.id to runCatching { recommend.fit(song.id)?.recommendedKey }.getOrNull() }
                    _state.update { it.copy(keys = it.keys + keys) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    private companion object { const val PAGE_SIZE = 100 }
}

/**
 * Figma "노래 연습 - 곡 선택": 헤더, 검색, 언어·분류 필터, LP 아트 행(분류 배지 · 찜)
 *
 * @param backTitle 있으면 탭 헤더(로고) 대신 뒤로가기 헤더를 그린다 — 다른 흐름(불러서 분석하기)이 곡 선택기로 재사용할 때.
 * @param subtitle 검색창 위 한 줄 안내(backTitle 과 함께 쓴다)
 */
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
    val vm: SongListViewModel = viewModel(factory = viewModelFactory { initializer { SongListViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val liked by container.favorites.likedIds.collectAsStateWithLifecycle()
    val loggedIn = TokenManager.isLoggedIn()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 찜은 서버가 진실. 로그인 상태면 진입 때 캐시를 한 번 맞춘다(비회원은 서버를 부르지 않는다).
    LaunchedEffect(loggedIn) { if (loggedIn) runCatching { container.favorites.refresh() } }
    fun like(songId: String) {
        if (!loggedIn) {
            onLogin?.invoke() ?: Toast.makeText(context, "로그인하면 찜할 수 있어요.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching { container.favorites.toggle(songId) }
                .onFailure { Toast.makeText(context, describeBackendError(it), Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (backTitle != null) {
            BackHeader(backTitle, onBack)
            Spacer(Modifier.height(12.dp))
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = Grey, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(10.dp))
            }
        } else {
            MiconHeader(onSettings = onSettings, onLogin = onLogin)
        }
        MiconSearchBar(state.query, vm::setQuery, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "불러오는 중…" else "${state.totalElements}곡" + (if (state.songs.size < state.totalElements) " 중 ${state.songs.size}곡 표시" else ""),
                fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f),
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
                        onLike = { like(song.id) },
                    )
                }
                if (state.songs.isEmpty()) item { Text("검색 결과가 없습니다.", Modifier.padding(24.dp), color = Grey) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
