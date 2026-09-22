package com.micon.app.ui.songlist

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.PreferenceRepository
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.LoadMoreFooter
import com.micon.app.ui.common.LoadMoreOnScrollEnd
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SongListViewModel(
    private val repo: SongRepository,
    private val recommend: RecommendRepository,
    private val preferences: PreferenceRepository,
) : ViewModel() {
    data class State(
        val query: String = "",
        val genres: List<PreferenceGenre> = emptyList(),
        val selectedGenreId: Long? = null,
        val songs: List<SongSummary> = emptyList(),
        val loading: Boolean = true,              // 첫 페이지(또는 검색어 변경) 로딩
        val totalElements: Long = 0,              // 서버 전체 곡 수 (검색어·장르 기준)
        val error: String? = null,
        val page: Int = 0,                        // 마지막으로 받은 페이지 번호(0부터)
        val hasMore: Boolean = false,             // 다음 페이지가 있는가
        val loadingMore: Boolean = false,         // 다음 페이지 로딩 중
        val moreError: String? = null,            // 다음 페이지 실패(목록 꼬리에 표시, 다시 시도 가능)
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null
    /** 첫 페이지·다음 페이지 요청 하나만 살아 있다. 검색어가 바뀌면 진행 중인 요청은 버린다. */
    private var pageJob: Job? = null
    private var requestGeneration = 0L

    init {
        loadFirstPage(forceNetwork = false)
        loadGenres()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        scheduleFirstPage()
    }

    /** 장르가 바뀌면 첫 페이지부터 다시 요청한다. null은 전체 장르다. */
    fun setGenre(genreId: Long?) {
        if (_state.value.selectedGenreId == genreId) return
        _state.update { it.copy(selectedGenreId = genreId) }
        scheduleFirstPage()
    }

    /**
     * 백엔드 `GET /songs` 는 제목·가수 검색과 선택 장르·페이지만 지원한다(페이지 크기 최대 100).
     * 첫 페이지를 받고, 나머지는 스크롤이 끝에 닿을 때 [loadMore] 로 이어 받는다(무한 스크롤).
     */
    fun refresh(forceNetwork: Boolean = true) {
        searchJob?.cancel()
        pageJob?.cancel()
        loadFirstPage(forceNetwork = forceNetwork)
    }

    private fun scheduleFirstPage() {
        searchJob?.cancel()
        pageJob?.cancel()
        requestGeneration++
        searchJob = viewModelScope.launch { delay(300); loadFirstPage(forceNetwork = false) }
    }

    private fun loadFirstPage(forceNetwork: Boolean) {
        pageJob?.cancel()
        val generation = ++requestGeneration
        pageJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, moreError = null, loadingMore = false) }
            val request = _state.value
            if (forceNetwork) repo.invalidateSearchCache(request.query, PAGE_SIZE, request.selectedGenreId)
            try {
                val page = repo.search(
                    query = request.query,
                    page = 0,
                    size = PAGE_SIZE,
                    genreId = request.selectedGenreId,
                )
                if (generation == requestGeneration) _state.update { it.copy(songs = page.items, totalElements = page.totalElements, loading = false,
                    page = page.page, hasMore = hasNext(page)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == requestGeneration) _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
        }
    }

    /** 다음 페이지를 붙인다. 이미 불러오는 중이거나 더 없으면 무시. 실패는 [State.moreError] 로만 알리고 기존 목록은 유지한다. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        pageJob?.cancel()
        val generation = requestGeneration
        pageJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreError = null) }
            try {
                val page = repo.search(
                    query = s.query,
                    page = s.page + 1,
                    size = PAGE_SIZE,
                    genreId = s.selectedGenreId,
                )
                if (generation == requestGeneration) _state.update { cur ->
                    val known = cur.songs.mapTo(HashSet()) { it.id }
                    cur.copy(songs = cur.songs + page.items.filter { it.id !in known }, totalElements = page.totalElements,
                        page = page.page, hasMore = hasNext(page), loadingMore = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == requestGeneration) _state.update { it.copy(loadingMore = false, moreError = e.message ?: "다음 곡을 불러오지 못했어요.") } }
        }
    }

    /** 서버 페이지 정보로 다음 페이지 유무 판단. totalPages 를 안 주면 받은 개수가 페이지 크기와 같을 때 더 있다고 본다. */
    private fun hasNext(page: SongPage): Boolean =
        if (page.totalPages > 0) page.page + 1 < page.totalPages else page.items.size >= PAGE_SIZE

    private fun loadGenres() {
        viewModelScope.launch {
            runCatching { preferences.getGenres() }
                .onSuccess { genres -> _state.update { it.copy(genres = genres) } }
        }
    }

    private companion object { const val PAGE_SIZE = 50 }
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
    val vm: SongListViewModel = viewModel(factory = viewModelFactory {
        initializer {
            SongListViewModel(
                container.songs,
                container.recommend,
                NetworkPreferenceRepository(container.backend.retrofit.create(PreferenceApi::class.java)),
            )
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume { if (!vm.state.value.loading) vm.refresh(forceNetwork = false) }
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

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC))) {
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
        SongCatalogContent(
            state = state,
            onQueryChange = vm::setQuery,
            onGenreChange = vm::setGenre,
            onRefresh = vm::refresh,
            onLoadMore = vm::loadMore,
            onSongClick = onSongClick,
            likedIds = liked,
            onLike = ::like,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 연습 곡 선택과 세트리스트 곡 추가가 함께 사용하는 전체 곡 카탈로그 UI. */
@Composable
fun SongCatalogContent(
    state: SongListViewModel.State,
    onQueryChange: (String) -> Unit,
    onGenreChange: (Long?) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    likedIds: Set<String>? = null,
    onLike: (String) -> Unit = {},
    excludedIds: Set<String> = emptySet(),
    searchPlaceholder: String = "노래나 가수를 검색해 보세요",
) {
    val visibleSongs = state.songs.filterNot { it.id in excludedIds }
    LaunchedEffect(visibleSongs.size, state.hasMore, state.loading, state.loadingMore, excludedIds) {
        if (!state.loading && !state.loadingMore && visibleSongs.isEmpty() && state.hasMore) onLoadMore()
    }
    Column(modifier) {
        MiconSearchBar(
            state.query,
            onQueryChange,
            Modifier.padding(horizontal = 20.dp),
            placeholder = searchPlaceholder,
            prototypeStyle = true,
        )
        Spacer(Modifier.height(12.dp))
        if (state.genres.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenreFilterChip("전체", state.selectedGenreId == null) { onGenreChange(null) }
                state.genres.forEach { genre ->
                    GenreFilterChip(genre.name, state.selectedGenreId == genre.genreId) {
                        onGenreChange(genre.genreId)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "불러오는 중…" else "${state.totalElements}곡" + (if (state.songs.size < state.totalElements) " 중 ${visibleSongs.size}곡 표시" else ""),
                fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = GreyLight)
        when {
            state.error != null -> RetryBlock(state.error!!, onRefresh)
            state.loading && state.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            else -> {
                val listState = rememberLazyListState()
                // 끝에 가까워지면 다음 페이지. 불러오는 중·실패·더 없음이면 기다리지 않는다(실패는 꼬리의 "다시 시도").
                LoadMoreOnScrollEnd(listState, enabled = state.hasMore && !state.loadingMore && state.moreError == null,
                    itemCount = visibleSongs.size, key = state.page, onLoadMore = onLoadMore)
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(visibleSongs, key = { it.id }) { song ->
                        SongRow(
                            title = song.title,
                            artist = song.artist,
                            imageUrl = song.imageUrl,
                            onClick = { onSongClick(song.id) },
                            // 일반 목록은 곡별 /fit 호출을 하지 않는다. 권장 키는 상세에서만 계산한다.
                            keyBadge = null,
                            liked = likedIds?.let { song.id in it },
                            onLike = { onLike(song.id) },
                            compactCd = true,
                        )
                    }
                    if (visibleSongs.isEmpty() && !state.hasMore) item { Text("검색 결과가 없습니다.", Modifier.padding(24.dp), color = Grey) }
                    item { LoadMoreFooter(loading = state.loadingMore, error = state.moreError, onRetry = onLoadMore) }
                }
            }
        }
    }
}

@Composable
private fun GenreFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) Purple else Color.White,
        border = BorderStroke(1.dp, if (selected) Purple else Color(0xFFE1DFE8)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (selected) Color.White else Ink,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
