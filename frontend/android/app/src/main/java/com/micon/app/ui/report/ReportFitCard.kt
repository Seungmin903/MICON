package com.micon.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongFit
import com.micon.app.data.SongPage
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.local.UserStore
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.onboarding.ProfileSaveReceipt
import com.micon.app.data.recommend.SavedProfileSongFit
import com.micon.app.ui.charts.MetricBar
import com.micon.app.ui.charts.RingProgress
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.LoadMoreFooter
import com.micon.app.ui.common.LoadMoreOnScrollEnd
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 리포트 탭 "곡 적합도" 카드의 상태. 곡을 고르면 [RecommendRepository.fit] 으로 적합도를 읽는다
 * (백엔드 `/songs/{id}/fit` 우선, 서버 분석이 없으면 기기 음역 계산). 화면은 값을 그리기만 하고 계산하지 않는다.
 *
 * 곡 선택 후보: 로그인 사용자는 찜한 곡(`GET /users/me/favorites`), 없거나 비회원이면 곡 카탈로그 첫 페이지. 검색어가 있으면 `GET /songs?query`.
 */
class ReportFitViewModel(
    private val songs: SongRepository,
    private val favorites: FavoriteRepository,
    private val recommend: RecommendRepository,
    private val loggedIn: Boolean,
    private val savedProfileFit: (suspend (String) -> SongFit?)? = null,
) : ViewModel() {
    data class State(
        val selected: SongSummary? = null,
        val fit: SongFit? = null,
        val fitLoading: Boolean = false,
        /** [fit] 이 null 인 이유(평가 불가 안내). 곡을 고르기 전이나 fit 이 있으면 null */
        val fitNote: String? = null,
        val pickerOpen: Boolean = false,
        val query: String = "",
        /** true 면 후보가 찜 목록, false 면 카탈로그·검색 결과 */
        val fromFavorites: Boolean = false,
        val candidates: List<SongSummary> = emptyList(),
        val pickerLoading: Boolean = false,
        val pickerError: String? = null,
        /** 카탈로그·검색 결과 페이징(찜 목록은 한 번에 온다). 스크롤 끝에서 [loadMoreCandidates]. */
        val pickerPage: Int = 0,
        val pickerHasMore: Boolean = false,
        val pickerLoadingMore: Boolean = false,
        val pickerMoreError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    fun openPicker() {
        _state.update { it.copy(pickerOpen = true) }
        loadCandidates()
    }

    fun closePicker() = _state.update { it.copy(pickerOpen = false) }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        loadCandidates()
    }

    private fun loadCandidates() {
        loadJob?.cancel()
        val query = _state.value.query.trim()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(pickerLoading = true, pickerError = null, pickerMoreError = null, pickerLoadingMore = false) }
            try {
                var fromFavorites = false
                var hasMore = false
                var pageNo = 0
                val list = if (query.isEmpty()) {
                    val liked = if (loggedIn) runCatching { favorites.refresh() }.getOrDefault(emptyList()) else emptyList()
                    if (liked.isNotEmpty()) { fromFavorites = true; liked }
                    else songs.search("", page = 0, size = PAGE).also { hasMore = hasNext(it); pageNo = it.page }.items
                } else {
                    songs.search(query, page = 0, size = PAGE).also { hasMore = hasNext(it); pageNo = it.page }.items
                }
                _state.update { it.copy(candidates = list, fromFavorites = fromFavorites, pickerLoading = false,
                    pickerPage = pageNo, pickerHasMore = hasMore) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(pickerLoading = false, pickerError = describeBackendError(e, "곡을 불러오지 못했어요.")) }
            }
        }
    }

    /** 곡 선택 시트의 다음 페이지(카탈로그·검색 결과만). 검색어가 바뀌면 [loadCandidates] 가 이 요청을 취소한다. */
    fun loadMoreCandidates() {
        val s = _state.value
        if (s.fromFavorites || s.pickerLoading || s.pickerLoadingMore || !s.pickerHasMore) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(pickerLoadingMore = true, pickerMoreError = null) }
            try {
                val page = songs.search(s.query.trim(), page = s.pickerPage + 1, size = PAGE)
                _state.update { cur ->
                    val known = cur.candidates.mapTo(HashSet()) { it.id }
                    cur.copy(candidates = cur.candidates + page.items.filter { it.id !in known },
                        pickerPage = page.page, pickerHasMore = hasNext(page), pickerLoadingMore = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(pickerLoadingMore = false, pickerMoreError = describeBackendError(e, "다음 곡을 불러오지 못했어요.")) }
            }
        }
    }

    private fun hasNext(page: SongPage): Boolean =
        if (page.totalPages > 0) page.page + 1 < page.totalPages else page.items.size >= PAGE

    fun select(song: SongSummary) {
        _state.update { it.copy(selected = song, pickerOpen = false, fit = null, fitNote = null, fitLoading = true) }
        viewModelScope.launch {
            val fit = runCatching {
                if (savedProfileFit != null) savedProfileFit.invoke(song.id) else recommend.fit(song.id)
            }.getOrNull()
            _state.update { s ->
                if (s.selected?.id != song.id) s
                else s.copy(fit = fit, fitLoading = false, fitNote = if (fit != null) null else noteForMissingFit())
            }
        }
    }

    /** 적합도가 없는 이유: 내 음성 프로필이 없거나, 곡에 음역 정보가 없다(FIT-002·003: 0점과 평가 불가를 구분). */
    private fun noteForMissingFit(): String =
        if (savedProfileFit != null) "저장한 프로필의 곡 적합도 결과를 아직 받을 수 없어요. 잠시 후 다시 확인해 주세요."
        else if (UserStore.profile.value == null) "목소리를 먼저 분석하면 이 곡과의 적합도를 볼 수 있어요."
        else "이 곡은 음역 정보가 아직 없어 적합도를 계산할 수 없어요."

    private companion object { const val PAGE = 30 }
}

/** Figma "곡 적합도": 분석할 노래 선택 + 종합 링 + 음역·음색·박자 막대 + 추천 키. 값이 없는 항목은 0 이 아닌 "평가 불가"로 표시한다. */
@Composable
fun ReportFitCard(loggedIn: Boolean, savedProfile: ProfileSaveReceipt? = null) {
    val container = rememberAppContainer()
    val vm: ReportFitViewModel = viewModel(
        key = "report-fit-$loggedIn-${TokenManager.getUserId()}-${savedProfile?.sessionId}-${savedProfile?.version}",
        factory = viewModelFactory { initializer {
            val savedFit = savedProfile?.let { SavedProfileSongFit(container.backend.api, it.profileId) }
            ReportFitViewModel(container.songs, container.favorites, container.recommend, loggedIn, savedFit?.let { it::fit })
        } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.pickerOpen) SongPickerSheet(state, onQuery = vm::setQuery, onPick = vm::select, onDismiss = vm::closePicker, onLoadMore = vm::loadMoreCandidates)

    MiconCard {
        CardTitle("곡 적합도")
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(1.dp)
                .background(GreyLight.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clickable(onClick = vm::openPicker).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val song = state.selected
            VinylArt(song?.imageUrl, 52.dp, fallbackText = song?.title ?: "?")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("분석할 노래", fontSize = 11.sp, color = Grey)
                if (song == null) {
                    Text("노래를 선택하세요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Grey)
                } else {
                    Text(song.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(song.artist, fontSize = 12.sp, color = Grey)
                }
            }
            Icon(Icons.Filled.KeyboardArrowDown, "노래 선택", tint = Ink)
        }
        Spacer(Modifier.height(18.dp))
        val fit = state.fit
        when {
            state.selected == null -> Text("찜한 곡이나 검색한 곡을 고르면 내 목소리와 얼마나 맞는지 보여 드려요.", fontSize = 13.sp, color = Grey, lineHeight = 19.sp)
            state.fitLoading -> Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            fit == null -> Text(state.fitNote ?: "적합도를 계산할 수 없어요.", fontSize = 13.sp, color = Grey, lineHeight = 19.sp)
            else -> FitBody(fit)
        }
    }
}

@Composable
private fun FitBody(fit: SongFit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RingProgress((fit.overallFit * 100).roundToInt().coerceIn(0, 100))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricRow("음역", fit.rangeFit?.score)
            MetricRow("음색", fit.timbreFit)
            MetricRow("박자", fit.rhythmFit)
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.background(Purple, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("추천 key", fontSize = 12.sp, color = Color.White)
            val key = fit.recommendedKey
            Text(
                when { key == null -> "-"; key > 0 -> "+$key"; else -> "$key" },
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
    }
    fit.reasons.firstOrNull()?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, fontSize = 13.sp, color = Grey, lineHeight = 19.sp)
    }
}

/** 값이 있으면 막대, 없으면 "평가 불가"(데이터 없음 — 0점이 아니다). */
@Composable
private fun MetricRow(label: String, value: Float?) {
    if (value != null) {
        MetricBar(label, value.coerceIn(0f, 1f), trailing = "${(value * 100).roundToInt()}")
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(36.dp))
            Text("평가 불가", fontSize = 12.sp, color = Grey)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerSheet(state: ReportFitViewModel.State, onQuery: (String) -> Unit, onPick: (SongSummary) -> Unit, onDismiss: () -> Unit, onLoadMore: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("분석할 노래", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            MiconSearchBar(state.query, onQuery, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                when { state.query.isNotBlank() -> "검색 결과"; state.fromFavorites -> "찜한 곡"; else -> "전체 곡" },
                fontSize = 12.sp, color = Grey, modifier = Modifier.padding(horizontal = 20.dp),
            )
            when {
                state.pickerLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                state.pickerError != null -> Text(state.pickerError, color = Grey, modifier = Modifier.padding(20.dp))
                state.candidates.isEmpty() -> Text(if (state.query.isBlank()) "곡이 없어요." else "검색 결과가 없어요.", color = Grey, modifier = Modifier.padding(20.dp))
                else -> {
                    val listState = rememberLazyListState()
                    LoadMoreOnScrollEnd(listState, enabled = state.pickerHasMore && !state.pickerLoadingMore && state.pickerMoreError == null,
                        itemCount = state.candidates.size, key = state.pickerPage, onLoadMore = onLoadMore)
                    LazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
                        items(state.candidates, key = { it.id }) { song ->
                            SongRow(title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onPick(song) })
                        }
                        if (state.pickerHasMore || state.pickerLoadingMore || state.pickerMoreError != null) {
                            item { LoadMoreFooter(loading = state.pickerLoadingMore, error = state.pickerMoreError, onRetry = onLoadMore) }
                        }
                    }
                }
            }
        }
    }
}
