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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// 추천곡
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

    init { load() }

    fun setQuery(q: String) {
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

    /** 추천 섹션 불러오기. 상태: 로딩 → 성공 | 결과 없음 | 실패 */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, userName = UserStore.nickname)
            runCatching { recommend.shelves(limit = 30, reshuffle = reshuffle) }   // 필터를 걸어도 화면을 다시 채울 만큼 넉넉히
                .onSuccess { r -> _state.value = _state.value.copy(shelves = r.shelves, personalized = r.personalized, note = r.note, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    private fun search(q: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { songs.search(q, size = 10).items }
                .onSuccess { found ->
                    val items = found.mapIndexed { i, song -> RecommendedSong(i + 1, song, fit = null, reasons = listOf("검색 결과")) }
                    _state.value = _state.value.copy(searchItems = items, loading = false)
                }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString()) }
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
@Composable
fun RecommendScreen(onSongClick: (Long) -> Unit, onSettings: () -> Unit, onAnalyze: () -> Unit, onLogin: (() -> Unit)? = null) {
    val container = rememberAppContainer()
    val vm: RecommendViewModel = viewModel(factory = viewModelFactory { initializer { RecommendViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()

    // 온보딩 진입(리포트 탭 VoiceReportScreen 과 같은 배선). 추천 탭은 로그인 사용자만 오므로 owner 가 null 이면 진입하지 않는다.
    val context = LocalContext.current
    val owner = TokenManager.getUserId().takeIf { it > 0 }
    val entry: VoiceOnboardingEntryViewModel = viewModel(key = "voice-entry-$owner", factory = viewModelFactory {
        initializer {
            VoiceOnboardingEntryViewModel(
                persistence = owner?.let { VoiceOnboardingStore(context.applicationContext, it, container.backend.api) },
                isCurrentOwner = { owner != null && TokenManager.getUserId() == owner },
            )
        }
    })
    val entryState by entry.state.collectAsStateWithLifecycle()
    LaunchedEffect(entryState.navigate) {
        if (entryState.navigate) {
            if (owner != null && TokenManager.getUserId() == owner) onAnalyze()
            entry.navigationHandled()
        }
    }
    val pagerState = rememberPagerState { state.shelves.size.coerceAtLeast(1) }
    val shelf = state.shelves.getOrNull(pagerState.currentPage)

    /** 턴테이블 화면이 좌우로 넘길 수 있게, 지금 보고 있는 섹션의 곡 목록·제목·앨범 이미지를 함께 넘긴다 */
    fun open(item: RecommendedSong) {
        vm.onTap(item)
        val pageSongs = (shelf?.items ?: state.searchItems).map { it.song }
        setSongCarousel(
            ids = pageSongs.map { it.id },
            title = if (shelf != null && state.personalized) shelfTitle(shelf, state.userName) else "추천곡",
            images = pageSongs.associate { it.id to it.imageUrl },
        )
        onSongClick(item.song.id)
    }
    fun openId(id: Long) { state.allItems.firstOrNull { it.song.id == id }?.let(::open) ?: onSongClick(id) }

    // 시안: 검색바 없음, 제목 옆 🔍 만. 누르면 제목 자리가 검색창으로 바뀌고, 다시 누르면 검색을 접고 지운다.
    var searchOpen by remember { mutableStateOf(false) }
    var openFilter by remember { mutableStateOf<String?>(null) }     // "era" | "genre" — 열려 있는 드롭다운

    // 추천이 없을 때(분석 전 등)는 리포트 탭처럼 흰 배경. 보라 그라데이션은 LP 가 있을 때만.
    val empty = !state.loading && state.query.isBlank() && state.shelves.isEmpty()
    Column(Modifier.fillMaxSize().then(if (empty) Modifier.background(Color.White) else Modifier.background(RecommendBackground))) {
        MiconHeader(onSettings = onSettings, onLogin = onLogin)
        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searchOpen) {
                MiconSearchBar(state.query, vm::setQuery, Modifier.weight(1f))
            } else {
                // 제목 + (새로운 발견일 때만) 다시 뽑기 아이콘을 한 덩어리로 — 아이콘이 제목 **바로 옆**에 붙는다
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.personalized && shelf != null) shelfTitle(shelf, state.userName) else "추천곡",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (shelf?.key == ShelfKeys.DISCOVERY) {
                        IconButton(onClick = vm::refresh, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, "새로운 발견 다시 뽑기", tint = Purple)
                        }
                    }
                }
            }
            IconButton(onClick = { if (searchOpen) vm.setQuery(""); searchOpen = !searchOpen }) {
                Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, if (searchOpen) "검색 닫기" else "검색", tint = Ink)
            }
        }
        // 추천이 있을 때만 위에 한 줄 안내. 비었을 때의 이유는 아래 EmptyRecommend 가 크게 보인다.
        if (!state.loading && state.query.isBlank() && state.shelves.isNotEmpty() && state.note != null) {
            Text(state.note!!, fontSize = 13.sp, color = Grey, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.height(10.dp))
        // 필터 바. 성별·인기는 카탈로그에 데이터가 없어(artist_gender null, 인기 지표 없음) 자리만 두고 흐리게. 걸러 볼 곡이 없으면 감춘다.
        if (!empty) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillChip("성별", selected = false, onClick = {}, chevron = true, fontSize = 14.sp, modifier = Modifier.alpha(0.45f))
            FilterChip(
                state.era ?: "시대", Icons.Outlined.CalendarMonth, selected = state.era != null, expanded = openFilter == "era",
                options = ERAS, onOpen = { openFilter = "era" }, onDismiss = { openFilter = null }, onPick = vm::setEra,
            )
            FilterChip(
                state.genre ?: "장르", Icons.Outlined.GridView, selected = state.genre != null, expanded = openFilter == "genre",
                options = state.genres, onOpen = { openFilter = "genre" }, onDismiss = { openFilter = null }, onPick = vm::setGenre,
            )
            PillChip(
                "인기", selected = false, onClick = {}, fontSize = 14.sp, modifier = Modifier.alpha(0.45f),
                leading = { Icon(Icons.Outlined.Whatshot, null, Modifier.size(18.dp), tint = Purple) },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.loading && state.shelves.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Purple, trackColor = GreyLight)
        Spacer(Modifier.height(4.dp))
        when {
            // 검색 중엔 페이저 대신 검색 결과 한 화면
            state.query.isNotBlank() -> when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                state.error != null -> RetryBlock(state.error!!, { vm.setQuery(state.query) })
                state.searchItems.isEmpty() -> EmptyRecommend(searching = true, onAction = {})
                else -> BubbleCluster(state.filtered(state.searchItems).map { it.song }, ::openId, Modifier.weight(1f))
            }
            state.loading && state.shelves.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null && state.shelves.isEmpty() -> RetryBlock(state.error!!, vm::load)
            // 추천 없음: 분석 전이면 분석하러, 그 외(생성 중·서버 불가 등)는 다시 불러오기
            state.note == RecommendShelves.NOTE_ANALYSIS_REQUIRED ->
                EmptyRecommend(searching = false, text = state.note!!, modifier = Modifier.weight(1f)) {
                    VoiceOnboardingEntry(entryState, entry::open, entry::resume, entry::startNew, entry::dismiss)
                }
            state.shelves.isEmpty() ->
                EmptyRecommend(searching = false, onAction = vm::load, actionLabel = "다시 불러오기", text = state.note ?: "조건에 맞는 추천곡이 없어요", modifier = Modifier.weight(1f))
            else -> HorizontalPager(pagerState, Modifier.weight(1f)) { page ->
                val items = state.pageItems(page, BUBBLE_WEIGHTS.size)
                if (items.isEmpty()) {
                    val filtering = state.era != null || state.genre != null
                    EmptyRecommend(searching = false, onAction = { vm.setEra(null); vm.setGenre(null) }, actionLabel = if (filtering) "필터 해제" else null, modifier = Modifier.fillMaxSize())
                } else {
                    BubbleCluster(items.map { it.song }, ::openId, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 드롭다운 필터 칩. 선택되면 보라 채움 + 선택값이 라벨. "전체" 로 해제. */
@Composable
private fun FilterChip(
    label: String, icon: ImageVector, selected: Boolean, expanded: Boolean, options: List<String>,
    onOpen: () -> Unit, onDismiss: () -> Unit, onPick: (String?) -> Unit,
) {
    Box {
        PillChip(
            label, selected = selected, onClick = onOpen, chevron = true, fontSize = 14.sp,
            leading = { Icon(icon, null, Modifier.size(18.dp), tint = if (selected) Color.White else Purple) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text("전체") }, onClick = { onPick(null); onDismiss() })
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); onDismiss() }) }
        }
    }
}

/**
 * 결과 없음 (ERR-005). actionLabel 이 있으면 버튼(필터 해제·다시 불러오기 등), [action] 을 주면 그 컴포저블(온보딩 진입 버튼 등)을 대신 놓는다.
 * [text] 로 이유를 바꿔 보일 수 있다. 남은 영역 가운데에 놓이고, 버튼은 리포트 탭의 "목소리 분석하러 가기"와 같은 크기(66dp, 좌우 28dp).
 */
@Composable
private fun EmptyRecommend(
    searching: Boolean, onAction: () -> Unit = {}, actionLabel: String? = null,
    text: String = if (searching) "검색 결과가 없어요" else "조건에 맞는 추천곡이 없어요",
    modifier: Modifier = Modifier.fillMaxWidth(),
    action: (@Composable () -> Unit)? = actionLabel?.let { { PrimaryButton(it, onAction, Modifier.fillMaxWidth(), height = 66.dp) } },
) {
    Column(modifier.padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, fontSize = 16.sp, color = InkSoft, lineHeight = 22.sp)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * 한 페이지에 보이는 LP 수와 상대 크기(1위가 가장 큼). **개수를 바꾸려면 이 목록만 늘리고 줄인다** —
 * 넓이 총합이 [BUBBLE_COVERAGE] 로 고정이라 장수를 늘리면 LP 가 그만큼 작아지고 **간격은 그대로** 유지된다.
 * (5장 → 8장으로 늘리면서 LP 지름이 정확히 81 % 가 됐다)
 */
private val BUBBLE_WEIGHTS = listOf(1f, 0.92f, 0.88f, 0.84f, 0.80f, 0.76f, 0.72f)

/** 원들이 영역 넓이에서 차지할 목표 비율. 이 값이 LP 사이 간격을 정한다 — 높일수록 빽빽하다. */
private const val BUBBLE_COVERAGE = 0.62f

/**
 * 원 배치 계산. 가로 1 · 세로 [aspect] 인 영역에 원들을 **겹치지 않고 영역 안쪽에 완전히** 놓는다.
 * 반환은 (중심x, 중심y, 지름)이고 단위는 모두 가로 폭.
 *
 * 넓이 비율([coverage])로 지름을 정하고 → 해바라기 나선으로 고르게 뿌린 뒤 → "겹친 만큼 밀어내기 + 영역 안으로 당기기"를 반복한다.
 * LP 를 화면 밖으로 흘려 보내면(bleed) 위쪽 필터 바와 겹쳐 보이고 잘린 모양도 예쁘지 않아 쓰지 않는다 — 전부 안쪽에 넣는다.
 * 화면 비율마다 그때그때 다시 풀기 때문에 기기가 달라도 결과가 깨지지 않는다. 못 풀면 3 % 줄여 다시(최대 10 회).
 */
internal fun packBubbles(
    aspect: Float,
    weights: List<Float> = BUBBLE_WEIGHTS,
    coverage: Float = BUBBLE_COVERAGE,
): List<Triple<Float, Float, Float>> {
    val n = weights.size
    var k = sqrt(coverage * aspect / (PI.toFloat() / 4 * weights.sumOf { (it * it).toDouble() }.toFloat()))
    var last = emptyList<Triple<Float, Float, Float>>()
    repeat(10) {
        val r = FloatArray(n) { weights[it] * k / 2 }
        val x = FloatArray(n); val y = FloatArray(n)
        for (i in 0 until n) {                                   // 해바라기 나선 — 결정적이고 고르게 퍼진다
            val t = sqrt((i + 0.4f) / n)
            val a = i * GOLDEN_ANGLE
            x[i] = 0.5f + 0.50f * t * cos(a)
            y[i] = aspect / 2 + aspect / 2 * t * sin(a)
        }
        repeat(600) {
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val d = maxOf(hypot(dx, dy), 1e-6f)
                val need = r[i] + r[j]
                if (d < need) {
                    val push = (need - d) / d / 2
                    x[i] -= dx * push; y[i] -= dy * push
                    x[j] += dx * push; y[j] += dy * push
                }
            }
            for (i in 0 until n) {                               // 영역 안으로 (반지름이 커 범위가 뒤집혀도 예외 없이)
                x[i] = x[i].coerceAtLeast(r[i]).coerceAtMost(1f - r[i])
                y[i] = y[i].coerceAtLeast(r[i]).coerceAtMost(aspect - r[i])
            }
        }
        last = List(n) { Triple(x[it], y[it], 2 * r[it]) }
        // 영역 안에 다 들어오고(장수가 적으면 원이 영역보다 커질 수 있다) 서로 겹치지 않아야 통과
        val inside = (0 until n).all { i -> 2 * r[i] <= 1f + 1e-3f && 2 * r[i] <= aspect + 1e-3f }
        val apart = (0 until n).all { i -> (i + 1 until n).all { j -> hypot(x[j] - x[i], y[j] - y[i]) >= r[i] + r[j] - 1e-3f } }
        if (inside && apart) return last
        k *= 0.97f
    }
    return last
}

private const val GOLDEN_ANGLE = 2.39996f

/**
 * LP 버블 군집. 원 배치는 [packBubbles] 가 화면 비율에 맞춰 풀기 때문에 **겹치지 않고 잘리지도 않는다**.
 * 곡이 슬롯 수보다 적으면 있는 만큼만 그리되 그 수에 맞춰 다시 풀어 화면을 채운다.
 */
@Composable
private fun BubbleCluster(songs: List<SongSummary>, onSongClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val aspect = maxHeight / maxWidth
        val n = songs.size.coerceAtMost(BUBBLE_WEIGHTS.size)
        if (n == 0) return@BoxWithConstraints
        val slots = remember(aspect, n) { packBubbles(aspect, BUBBLE_WEIGHTS.take(n)) }
        songs.take(n).forEachIndexed { i, song ->
            val (cx, cy, d) = slots[i]
            val size = w * d
            Box(
                Modifier.offset(x = w * cx - size / 2, y = w * cy - size / 2).size(size).clickable { onSongClick(song.id) },
            ) { VinylArt(song.imageUrl, size, fallbackText = song.title) }
        }
    }
}

// ---------------------------------------------------------------------------
// song-match-detail (곡 분석 및 추천)
// ---------------------------------------------------------------------------

class SongMatchViewModel(private val repo: SongRepository, private val recommend: RecommendRepository, private val songId: Long) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        /** fit == null 이면 평가 불가(프로필 없음 또는 곡 음역 정보 없음, ERR-004) */
        data class Ready(val detail: SongDetail, val fit: SongFit?) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = State.Loading
            _state.value = runCatching { repo.detail(songId) to recommend.fit(songId) }.fold(
                onSuccess = { (d, fit) -> d?.let { State.Ready(it, fit) } ?: State.Error("곡을 찾을 수 없습니다: $songId") },
                onFailure = { State.Error(it.message ?: it.toString()) },
            )
        }
    }
}

/** MIDI → "2옥 도" 식 국내 노래방 옥타브 표기 (C4 = 2옥 도). */
fun karaokeOctaveName(midi: Int): String {
    val names = listOf("도", "도#", "레", "레#", "미", "파", "파#", "솔", "솔#", "라", "라#", "시")
    val octave = midi / 12 - 3 // C3(48) → 1옥
    return "${octave}옥 ${names[midi % 12]}"
}

@Composable
fun SongMatchDetailScreen(songId: Long, onBack: () -> Unit, onPractice: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SongMatchViewModel = viewModel(key = "match-$songId", factory = viewModelFactory { initializer { SongMatchViewModel(container.songs, container.recommend, songId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(GreyBg)) {
        BackHeader("곡 분석 및 추천", onBack)
        when (val s = state) {
            SongMatchViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            is SongMatchViewModel.State.Error -> RetryBlock(s.message, vm::load)
            is SongMatchViewModel.State.Ready -> MatchBody(s.detail, s.fit, profile, onPractice)
        }
    }
}

/** 분석 본문 색. 흰 화면(기존 상세)과 검은 시트(턴테이블) 두 곳에서 같은 내용을 쓴다. */
internal data class MatchPalette(
    val card: Color, val sub: Color, val title: Color, val body: Color, val muted: Color, val track: Color, val accent: Color,
)

internal val LightMatch = MatchPalette(Paper, GreyBg, Ink, InkSoft, Grey, GreyLight, Purple)
internal val DarkMatch = MatchPalette(
    card = Color(0xFF1A2230), sub = Color(0xFF141B27), title = Color.White,
    body = Color(0xFFD3D8E3), muted = Color(0xFF98A0B0), track = Color(0xFF2A3444), accent = Color(0xFFB3A5FF),
)

/**
 * 곡 분석 및 추천 본문. 적합도·추천 키·부담 구간은 [SongFit] 을 **그리기만** 한다(계산은 RecommendRepository).
 * fit == null → "평가 불가" (프로필 없음 또는 곡 음역 정보 없음, ERR-004). 음색·리듬은 데이터가 없으면 0점이 아닌 "평가 불가".
 *
 * [showHeader] 를 끄면 맨 위 곡 제목 카드를 그리지 않는다 — 턴테이블 화면은 시트 머리에 이미 제목·가수가 있다.
 * [scroll] 을 넘기면 바깥 스크롤에 얹혀 그려진다(자체 스크롤 없음).
 */
@Composable
internal fun MatchBody(
    detail: SongDetail,
    fit: SongFit?,
    profile: UserStore.VoiceProfile?,
    onPractice: () -> Unit,
    palette: MatchPalette = LightMatch,
    showHeader: Boolean = true,
    ownScroll: Boolean = true,
) {
    val song = detail.summary
    val a = detail.analysis
    // 곡 음역: 로컬 분석이 있으면 전체 음역, 없으면(서버 추천 곡) 서버가 준 주요 음역(5~95 %)
    val coreOnly = a?.lowestMidi == null && fit?.rangeFit?.songCoreLowMidi != null
    val songLow = a?.lowestMidi ?: fit?.rangeFit?.songCoreLowMidi ?: 60
    val songHigh = a?.highestMidi ?: fit?.rangeFit?.songCoreHighMidi ?: 72
    val hasSongRange = a?.lowestMidi != null || coreOnly
    val userLow = profile?.lowMidi ?: songLow
    val userHigh = profile?.highMidi ?: songHigh
    val fitPct = fit?.let { (it.overallFit * 100).roundToInt() }
    val fitLabel = when { fitPct == null -> "평가 불가"; fitPct >= 90 -> "찰떡궁합!"; fitPct >= 75 -> "잘 맞아요"; else -> "도전해 보세요" }
    val key = fit?.recommendedKey
    val keyText = when { key == null -> "평가 불가"; key == 0 -> "원곡 키 추천 (0)"; key > 0 -> "키 +$key 추천"; else -> "키 $key 추천" }
    val axisLow = minOf(songLow, userLow) - 2
    val axisHigh = maxOf(songHigh, userHigh) + 2

    val scrollMod = if (ownScroll) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
    Column(scrollMod.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showHeader) {
            MiconCard(color = palette.card) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(LavenderDeep)) {
                        song.imageUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        // 적합도는 숫자 대신 말로만 (찰떡궁합 / 잘 맞아요 / 도전해 보세요)
                        Text(fitLabel, fontSize = 14.sp, color = palette.accent, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.title)
                        Spacer(Modifier.height(4.dp))
                        Text(song.artist, fontSize = 15.sp, color = palette.muted)
                    }
                }
            }
        }

        MiconCard(color = palette.card) {
            CardTitle("음역대 매칭 분석", color = palette.title) {
                Text(
                    when { fit == null -> "데이터 부족"; fitPct!! >= 75 -> "안정적인 소화 가능"; else -> "일부 구간 주의" },
                    fontSize = 14.sp, color = palette.accent, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1옥타브 (Low)", fontSize = 12.sp, color = palette.muted); Text("2옥타브", fontSize = 12.sp, color = palette.muted); Text("3옥타브 (High)", fontSize = 12.sp, color = palette.muted)
            }
            Spacer(Modifier.height(12.dp))
            if (profile != null) {
                RangeBarRow("내 음역 범위 (${karaokeOctaveName(profile.lowMidi)} ~ ${karaokeOctaveName(profile.highMidi)})", profile.lowMidi, profile.highMidi, axisLow, axisHigh, LavenderDeep, palette)
            } else {
                Text("내 음역 범위: 목소리 분석 후 표시됩니다", fontSize = 13.sp, color = palette.muted)
            }
            Spacer(Modifier.height(12.dp))
            if (hasSongRange) {
                RangeBarRow(
                    (if (coreOnly) "곡 주요 음역 " else "곡 음역 범위 ") + "(${karaokeOctaveName(songLow)} ~ ${karaokeOctaveName(songHigh)})",
                    songLow, songHigh, axisLow, axisHigh, palette.accent, palette,
                )
            } else {
                Text("곡 음역 범위: 분석 정보 없음", fontSize = 13.sp, color = palette.muted)
            }
            Spacer(Modifier.height(14.dp))
            if (profile == null) {
                Text("리포트 탭에서 목소리를 분석하면 이 곡과 내 음역을 비교해 드려요.", fontSize = 15.sp, color = palette.body, lineHeight = 22.sp)
            } else if (!hasSongRange) {
                Text("이 곡은 음역 분석 정보가 없어 적합도를 평가할 수 없어요.", fontSize = 15.sp, color = palette.body, lineHeight = 22.sp)
            } else if (coreOnly) {
                Text("곡의 주요 음역(자주 부르는 5~95 % 구간)은 추천 서버 분석 기준이에요. 최고음·최저음은 곡 분석이 추가되면 표시됩니다.", fontSize = 15.sp, color = palette.body, lineHeight = 22.sp)
            } else {
                Text(
                    buildAnnotatedString {
                        append("이 곡의 최고음은 ")
                        withStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold)) { append(karaokeOctaveName(songHigh)) }
                        append("로, 회원님의 안정 음역대인 ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(karaokeOctaveName(profile.comfortHigh)) }
                        append(
                            if (songHigh <= profile.comfortHigh) "보다 낮거나 같아 원키로도 무리가 적습니다."
                            else "보다 ${songHigh - profile.comfortHigh}키 높아 고음 구간에서 부담이 있을 수 있습니다.",
                        )
                    },
                    fontSize = 15.sp, color = palette.body, lineHeight = 22.sp,
                )
            }
        }

        MiconCard(color = palette.card) {
            CardTitle("곡 맞춤 설정 추천", color = palette.title)
            Spacer(Modifier.height(14.dp))
            SubCard("권장 키 조절", keyText, palette) {
                val rangeReason = fit?.reasons?.firstOrNull { it != fit.timbre?.label }
                Text(
                    rangeReason ?: when { fit == null -> "곡 음역 정보와 내 프로필이 있어야 계산돼요"; key == 0 -> "원곡 키가 내 주요 음역과 맞아요"; else -> "곡의 주요 음역을 내 음역에 맞추는 조정이에요" },
                    fontSize = 13.sp, color = palette.accent,
                )
            }
            Spacer(Modifier.height(12.dp))
            SubCard("음색 적합도", fit?.timbre?.label ?: "평가 불가", palette) {
                Text(if (fit?.timbre != null) "두 축 중 ${fit.timbre.axis} 기준" else "음색 추천은 서버 연결 후 제공돼요", fontSize = 13.sp, color = palette.muted)
            }
            // 리듬 적합도는 데이터가 없어 "평가 불가"만 뜨므로 카드를 두지 않는다. 리듬 분석이 생기면 SongFit.rhythmFit 으로 되살린다.
        }

        MiconCard(color = palette.card) {
            val strain = fit?.strainSections
            CardTitle("부담 구간", color = palette.title) {
                Text(
                    if (strain == null) "평가 불가" else "원키 ${strain.original.size}곳 → 추천 키 ${strain.atKey.size}곳",
                    fontSize = 13.sp, color = palette.muted,
                )
            }
            Spacer(Modifier.height(14.dp))
            when {
                strain == null -> Text("프로필과 곡 음표가 있어야 계산돼요", fontSize = 13.sp, color = palette.muted)
                strain.atKey.isEmpty() -> Text("추천 키로 부르면 안정 음역을 넘는 구간이 없어요", fontSize = 14.sp, color = palette.body)
                else -> strain.atKey.take(5).forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    HighlightRow("부담", "안정 음역 +${s.maxExcessSemitones}반음 초과", "${ScoringMath.formatTime(s.startSec)} ~ ${ScoringMath.formatTime(s.endSec)}", "고음 주의", palette)
                }
            }
        }

        // 추천 사유 카드는 두지 않는다 — 첫 사유는 "권장 키 조절" 안에서 이미 보인다.
        PrimaryButton("이 곡 연습하기", onPractice, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RangeBarRow(label: String, low: Int, high: Int, axisLow: Int, axisHigh: Int, color: Color, palette: MatchPalette) {
    Column {
        Text(label, fontSize = 13.sp, color = palette.body)
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(palette.track)) {
            val span = (axisHigh - axisLow).coerceAtLeast(1)
            val x0 = maxWidth * (low - axisLow) / span
            val x1 = maxWidth * (high - axisLow) / span
            Box(Modifier.offset(x = x0).width(x1 - x0).height(10.dp).background(color, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun SubCard(label: String, value: String, palette: MatchPalette, stars: Int = 0, hint: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(palette.sub, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(label, fontSize = 13.sp, color = palette.muted)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.title)
            if (stars > 0) {
                Spacer(Modifier.width(6.dp))
                repeat(3) { i -> Icon(if (i < stars) Icons.Filled.Star else Icons.Outlined.StarOutline, null, Modifier.size(18.dp), tint = Orange) }
            }
        }
        Spacer(Modifier.height(4.dp))
        hint()
    }
}

@Composable
private fun HighlightRow(badge: String, title: String, time: String, tag: String, palette: MatchPalette) {
    Row(Modifier.fillMaxWidth().background(palette.sub, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SoftChip(badge, bg = PinkBg, fg = Pink)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.title)
            Text(time, fontSize = 13.sp, color = palette.muted)
        }
        Text(tag, fontSize = 13.sp, color = Pink, fontWeight = FontWeight.SemiBold)
    }
}

@Suppress("unused")
private val unusedPurpleMid = PurpleMid
@Suppress("unused")
private val unusedLavender = Lavender
