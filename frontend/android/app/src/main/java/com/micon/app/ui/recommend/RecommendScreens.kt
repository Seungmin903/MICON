package com.micon.app.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.RecommendEvent
import com.micon.app.data.RecommendEventLog
import com.micon.app.data.RecommendRepository
import com.micon.app.data.RecommendShelf
import com.micon.app.data.RecommendShelves
import com.micon.app.data.RecommendedSong
import com.micon.app.data.ShelfKeys
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.local.UserStore
import com.micon.app.data.onboarding.VoiceOnboardingStore
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.KeyBadge
import com.micon.app.ui.common.keyBadgeText
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.onboarding.VoiceOnboardingEntry
import com.micon.app.ui.onboarding.VoiceOnboardingEntryViewModel
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Orange
import com.micon.app.ui.theme.Paper
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.PinkBg
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import com.micon.app.ui.theme.RecommendBackground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// 추천곡 — 상태·로딩·로그. 화면은 RecommendDeckScreen.kt
// ---------------------------------------------------------------------------

class RecommendViewModel(private val songs: SongRepository, private val recommend: RecommendRepository) : ViewModel() {
    data class State(
        val query: String = "",
        /** 검색 결과 — query 가 있을 때만 */
        val searchItems: List<RecommendedSong> = emptyList(),
        /** 추천 섹션(페이지). 저장소가 정한 순서·곡 그대로, 화면은 재정렬하지 않는다(REC-001) */
        val shelves: List<RecommendShelf> = emptyList(),
        /** false = 개인화 문구(이름·"내 음색")를 쓰지 않는다 */
        val personalized: Boolean = false,
        /** 서버 추천 대신 기기 계산을 보여 주는 이유, 또는 shelves 가 빈 이유(로그인·분석 필요). null 이면 없음 */
        val note: String? = null,
        val loading: Boolean = true,
        val generating: Boolean = false,
        val error: String? = null,
        /** 필터 바(시안: 성별·시대·장르·인기). null = 전체. 성별·인기는 데이터가 아직 없어 화면에서 비활성 */
        val era: String? = null,
        val genre: String? = null,
        /** 제목에 부를 이름. null 이면 "내 …" */
        val userName: String? = null,
    ) {
        /** 추천받은 곡에 필터만 적용 — 순서는 그대로 */
        fun filtered(items: List<RecommendedSong>) =
            items.filter { (era == null || eraOf(it.song.releaseDate) == era) && (genre == null || it.song.genre == genre) }

        val filtering: Boolean get() = era != null || genre != null

        /**
         * 한 페이지에 채울 곡. **필터 때문에** 그 섹션 곡이 줄면 다른 섹션의 같은 조건 곡으로 채워 화면이 비지 않게 한다(곡 중복 없음).
         * 그래서 필터는 LP 수를 줄이는 게 아니라 보이는 곡을 갈아 끼운다.
         * 필터가 없는데도 곡이 적은 섹션은 그대로 둔다 — 남의 섹션 곡으로 채우면 섹션 제목이 거짓말이 된다.
         */
        fun pageItems(page: Int, slots: Int): List<RecommendedSong> {
            val shelf = shelves.getOrNull(page) ?: return emptyList()
            val primary = filtered(shelf.items)
            if (primary.size >= slots || !filtering) return primary.take(slots)
            val seen = primary.mapTo(HashSet()) { it.song.id }
            val fill = shelves.filterIndexed { i, _ -> i != page }.flatMap { filtered(it.items) }.filter { seen.add(it.song.id) }
            return (primary + fill).take(slots)
        }

        /** 장르 필터 선택지: 지금 받은 섹션들에 실제로 있는 장르만 */
        val genres: List<String> get() = shelves.flatMap { it.items }.mapNotNull { it.song.genre }.distinct()

        val allItems: List<RecommendedSong> get() = shelves.flatMap { it.items } + searchItems
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0L
    private var visible = false

    init {
        viewModelScope.launch {
            UserStore.voiceDataRevision.collect { load(clear = true) }
        }
    }

    fun onVisible() { visible = true; load(clear = true) }
    fun onHidden() { visible = false; generation++; loadJob?.cancel(); searchJob?.cancel() }

    fun setQuery(q: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(query = q, searchItems = emptyList())
        if (q.isNotBlank()) search(q)
    }

    fun setEra(era: String?) { _state.value = _state.value.copy(era = era) }
    fun setGenre(genre: String?) { _state.value = _state.value.copy(genre = genre) }

    /** "새로운 발견"은 순서가 무작위라 다시 뽑을 수 있다 — 누를 때마다 섞임이 달라지게 세는 값 */
    private var reshuffle = 0

    /** 새로운 발견 다시 뽑기 (REC-006) */
    fun refresh() {
        reshuffle++
        load()
    }

    /** 서버 추천이 비었을 때 사용자가 눌러 다시 만든다. 자동으로는 부르지 않는다. */
    fun retry() {
        load(clear = true, regenerate = true)
    }

    /** 추천 섹션 불러오기. 상태: 로딩 → 성공 | 결과 없음 | 실패 */
    fun load(clear: Boolean = false, regenerate: Boolean = false) {
        val request = ++generation
        val revision = UserStore.voiceDataRevision.value
        loadJob?.cancel()
        if (clear) {
            searchJob?.cancel()
            _state.value = _state.value.copy(shelves = emptyList(), searchItems = emptyList(), personalized = false, note = null, generating = false)
        }
        if (!visible) return
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, userName = UserStore.nickname)
            try {
                if (regenerate) recommend.regenerate()
                // Only poll while this screen is visible; the next visit can resume polling.
                repeat(60) {
                    val r = recommend.shelves(limit = 30, reshuffle = reshuffle)
                    currentCoroutineContext().ensureActive()
                    if (request != generation || revision != UserStore.voiceDataRevision.value) return@launch
                    _state.value = _state.value.copy(shelves = r.shelves, personalized = r.personalized, note = r.note,
                        loading = false, generating = r.generating)
                    if (!r.generating) return@launch
                    delay(2_000)
                }
                if (request == generation && revision == UserStore.voiceDataRevision.value)
                    _state.value = _state.value.copy(generating = false,
                        error = "추천 준비가 예상보다 오래 걸리고 있어요. 잠시 후 다시 불러와 주세요.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == generation && revision == UserStore.voiceDataRevision.value)
                    _state.value = _state.value.copy(loading = false, generating = false, error = e.message ?: e.toString())
            }
        }
    }

    private fun search(q: String) {
        val request = generation
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                    val found = songs.search(q, size = 10).items
                    currentCoroutineContext().ensureActive()
                    if (request != generation || _state.value.query != q) return@launch
                    val items = found.mapIndexed { i, song -> RecommendedSong(i + 1, song, fit = null, reasons = listOf("검색 결과")) }
                    _state.value = _state.value.copy(searchItems = items, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == generation && _state.value.query == q)
                    _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    /** 카드 탭 로그 — 개인화의 유일한 데이터원(AI 파트 지시). 화면 위치와 축 라벨을 함께 남긴다. */
    fun onTap(item: RecommendedSong) {
        val userId = TokenManager.getUserId().takeIf { it >= 0 }?.toString() ?: "anonymous"
        RecommendEventLog.log(
            RecommendEvent(
                userId = userId, songId = item.song.id, timbreAxis = item.fit?.timbre?.axis, position = item.rank,
                action = RecommendEvent.Action.TAP, scoringVersion = item.fit?.scoringVersion ?: "none", compositionVersion = item.fit?.compositionVersion,
            ),
        )
    }
}

/**
 * 섹션 제목은 **앱이 정한다**(서버 title 대신). 이름을 모르면 "내 …" 로 쓴다. 모르는 key 는 서버 문구를 그대로.
 */
private fun shelfTitle(shelf: RecommendShelf, name: String?): String = when (shelf.key) {
    ShelfKeys.VOICE -> "${name?.plus("님") ?: "내"} 음색에 딱인 곡"
    ShelfKeys.ORIGINAL_KEY -> "내 음역대에 딱 맞는 곡"
    ShelfKeys.FAVORITE_ARTISTS -> "${name?.plus("님이") ?: "내가"} 좋아하는 가수의 곡"
    ShelfKeys.DISCOVERY -> "새로운 발견"
    else -> shelf.title
}

/** 시대 필터 선택지와 발매연도 → 시대 라벨 */
private val ERAS = listOf("2020년대", "2010년대", "2000년대", "1990년대", "그 이전")
fun eraOf(releaseDate: String?): String? {
    val y = releaseDate?.take(4)?.toIntOrNull() ?: return null
    return when { y >= 2020 -> "2020년대"; y >= 2010 -> "2010년대"; y >= 2000 -> "2000년대"; y >= 1990 -> "1990년대"; else -> "그 이전" }
}

/**
 * 추천 탭(시안). 페이지 하나 = 추천 섹션 하나(내 목소리와 닮은 곡 · 키 안 바꾸고 바로 부를 곡 · 선호 가수 · 새로운 발견), 좌우 스와이프로 이동.
 * 제목은 현재 섹션 제목. 칩은 **필터 바**(성별·시대·장르·인기) — 추천받은 곡을 걸러 보인다. 상태 4종(로딩·성공·결과 없음·실패).
 * 목소리 분석 전에는 추천이 없고 안내 + "목소리 분석하러 가기"(리포트 탭과 같은 [VoiceOnboardingEntry] — 진행 중 초안이 있으면
 * 이어하기/새로 측정을 묻고, 만료된 초안은 새 초안으로 바꾼 뒤 [onAnalyze] 로 온보딩에 들어간다)만 보인다.
 */
