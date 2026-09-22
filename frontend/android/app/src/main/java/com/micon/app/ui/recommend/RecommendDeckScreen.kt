package com.micon.app.ui.recommend

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.RecommendShelf
import com.micon.app.data.RecommendShelves
import com.micon.app.data.RecommendedSong
import com.micon.app.data.ShelfKeys
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.res.painterResource
import com.micon.app.R
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.CdPlayer
import com.micon.app.ui.design.tokens.MiconDesignTheme
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 추천 탭 — 포터블 CD 플레이어. 프로토타입 `#recommend` 화면.
 *
 * 흐름: 위 랙 칩으로 섹션을 고른다 → 아래 카드를 **위로 끌어 올리면** 데크에 CD 가 올라간다 →
 * ⏮ ⏭ 로 같은 섹션 안에서 갈아끼운다 → CD 가운데나 `이 곡 자세히 보기` 로 곡 상세 →
 * CD 를 **아래로 끌어내리면** 빼기. 올린 뒤에는 아래 디스크 줄에서 눌러서 바로 교체.
 *
 * 처음 한 번은 사용법 코치가 화면 전체(하단 탭 포함) 위에 뜬다. `?` 로 다시 볼 수 있다.
 * 데이터·로그·온보딩 진입은 [RecommendViewModel] 그대로 쓴다. 이 파일은 겉모습만 바꾼다.
 */
@Composable
fun RecommendDeckScreen(
    onSongClick: (String) -> Unit,
    onSettings: () -> Unit,
    onAnalyze: () -> Unit,
    onLogin: (() -> Unit)? = null,
) {
    val container = rememberAppContainer()
    val vm: RecommendViewModel = viewModel(factory = viewModelFactory { initializer { RecommendViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onVisible()
                Lifecycle.Event.ON_PAUSE -> vm.onHidden()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onVisible()
        onDispose { lifecycle.removeObserver(observer); vm.onHidden() }
    }

    val context = LocalContext.current

    // --- 화면 상태 ---
    var shelfIndex by rememberSaveable { mutableIntStateOf(0) }
    var loadedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var coachOpen by rememberSaveable { mutableStateOf(false) }

    val shelves = state.shelves
    val shelf = shelves.getOrNull(shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0)))
    val items = shelf?.let { state.filtered(it.items) } ?: emptyList()
    val loadedIndex = items.indexOfFirst { it.song.id == loadedId }
    val loaded = items.getOrNull(loadedIndex)

    // 첫 방문: 추천이 있고 CD 를 아직 안 올렸으면 코치를 한 번 띄운다
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    LaunchedEffect(shelves.isNotEmpty()) {
        if (shelves.isNotEmpty() && loadedId == null && !prefs.getBoolean(KEY_COACH_SEEN, false)) coachOpen = true
    }
    fun closeCoach() { coachOpen = false; prefs.edit().putBoolean(KEY_COACH_SEEN, true).apply() }

    val preview = rememberRecommendPreview(container.songs, container.mediaHttp)
    LaunchedEffect(loadedId) { if (loadedId != preview.songId) preview.stop() }

    fun open(item: RecommendedSong) {
        vm.onTap(item)
        val pageSongs = (if (state.query.isNotBlank()) state.searchItems else items).map { it.song }
        setSongCarousel(
            ids = pageSongs.map { it.id },
            title = shelf?.let { rackLabel(it) } ?: "추천곡",
            images = pageSongs.associate { it.id to it.imageUrl },
        )
        onSongClick(item.song.id)
    }

    // 끌어올리는 카드는 화면 맨 위 레이어에 따로 그린다 — LazyRow 안에 두면 행 밖으로 나가는 순간 잘려
    // 플레이어에 가린 것처럼 보였다.
    var floating by remember { mutableStateOf<FloatingCard?>(null) }

    MiconDesignTheme {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
            // 로고·설정 행과 ?·검색 행을 한 줄로 합쳤다 — 검색이 화면 맨 위에 온다
            TopBar(
                searchOpen = searchOpen,
                query = state.query,
                onQuery = vm::setQuery,
                onToggleSearch = { if (searchOpen) vm.setQuery(""); searchOpen = !searchOpen },
                onHelp = { coachOpen = true },
                onSettings = onSettings,
                onLogin = onLogin,
            )

            when {
                state.query.isNotBlank() -> SearchResults(state, onRetry = { vm.setQuery(state.query) }, onPick = ::open)

                state.loading && shelves.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = scheme.primary) }

                state.error != null && shelves.isEmpty() ->
                    EmptyState(text = state.error!!, actionLabel = "다시 불러오기", onAction = vm::load)

                state.note == RecommendShelves.NOTE_ANALYSIS_REQUIRED ->
                    EmptyState(text = state.note!!, actionLabel = "목소리 분석하기", onAction = onAnalyze)

                shelves.isEmpty() -> {
                    val serverEmpty = state.note?.startsWith(RecommendShelves.NOTE_EMPTY) == true
                    EmptyState(
                        text = state.note ?: "조건에 맞는 추천곡이 없어요",
                        actionLabel = if (serverEmpty) "추천 다시 만들기" else "다시 불러오기",
                        onAction = { if (serverEmpty) vm.retry() else vm.load() },
                    )
                }

                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MiconSpace.screenHorizontal, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs),
                    ) {
                        items(shelves.size) { i ->
                            MiconFilterChip(
                                text = rackLabel(shelves[i]),
                                selected = i == shelfIndex,
                                emphasis = true,
                                onClick = { if (i != shelfIndex) { shelfIndex = i; loadedId = null } },
                            )
                        }
                    }

                    CdPlayer(
                        title = loaded?.song?.title,
                        artist = loaded?.song?.artist,
                        imageUrl = loaded?.song?.imageUrl,
                        trackIndex = loadedIndex + 1,
                        trackTotal = items.size,
                        loaded = loaded != null,
                        playing = preview.playing && preview.songId == loadedId,
                        progress = if (preview.songId == loadedId) preview.progress else 0f,
                        onDiscClick = { loaded?.let { preview.toggle(it.song.id) } },
                        onPrev = { items.getOrNull(loadedIndex - 1)?.let { loadedId = it.song.id } },
                        onNext = { items.getOrNull(loadedIndex + 1)?.let { loadedId = it.song.id } },
                        onDiscDragDown = { loadedId = null },
                        modifier = Modifier.padding(horizontal = MiconSpace.screenHorizontal),
                    )

                    NowInfo(loaded, preview = preview.takeIf { it.songId == loadedId })

                    if (loaded == null) {
                        CardFan(
                            header = shelf?.let(::rackLabel).orEmpty(),
                            songs = items,
                            onLoad = { loadedId = it.song.id },
                            onFloat = { floating = it },
                        )
                    } else {
                        DiscRow(
                            songs = items.filter { it.song.id != loadedId },
                            onPick = { loadedId = it.song.id },
                        )
                    }

                    MiconButton(
                        text = "이 곡 자세히 보기",
                        onClick = { loaded?.let(::open) },
                        enabled = loaded != null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MiconSpace.screenHorizontal),
                    )

                    Spacer(Modifier.navigationBarsPadding().height(MiconSpace.navBarContentPadding))
                }
            }
        }

        floating?.let { FloatingDeckCard(it) }
        }

        if (coachOpen) CoachOverlay(onDismiss = ::closeCoach)
    }
}

/** 끌리는 중인 카드의 위치(루트 좌표)·들어올린 높이·기울기 */
private data class FloatingCard(
    val item: RecommendedSong,
    val origin: Offset,
    val liftPx: Float,
    val tilt: Float,
    val thresholdPx: Float,
)

/** 화면 최상위 레이어의 카드. [DragCard] 와 같은 변형 규칙을 쓴다 */
@Composable
private fun FloatingDeckCard(f: FloatingCard) {
    val p = (-f.liftPx / f.thresholdPx).coerceIn(0f, 1f)
    Box(
        Modifier
            .offset { IntOffset(f.origin.x.roundToInt(), (f.origin.y + f.liftPx).roundToInt()) }
            .width(96.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = 14f * f.tilt * (1f - p)
                translationY = abs(f.tilt) * 14.dp.toPx() * (1f - p)
                scaleX = 1f - 0.14f * p; scaleY = scaleX
                alpha = 1f - 0.25f * p
            },
    ) { DeckCardFace(f.item) }
}

private const val PREFS = "micon_ui"
private const val KEY_COACH_SEEN = "recommend_coach_seen"

// ---------------------------------------------------------------------------
// 상단 바
// ---------------------------------------------------------------------------

/** 공통 헤더 바로 아래의 도움말·검색 도구. 검색을 열면 입력창이 된다. */
@Composable
private fun TopBar(
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onLogin: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = MiconSpace.topBarMinHeight).padding(horizontal = MiconSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!searchOpen) {
            // 다른 탭 헤더와 같은 로고 한 장
            Spacer(Modifier.width(MiconSpace.sm))
            Image(
                painter = painterResource(R.drawable.micon_logo),
                contentDescription = "MICON",
                modifier = Modifier.height(22.dp).width(88.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (searchOpen) {
            Row(
                Modifier
                    .weight(1f)
                    .padding(start = MiconSpace.sm)
                    .height(44.dp)
                    .clip(MiconShape.field)
                    .background(scheme.surface)
                    .padding(horizontal = MiconSpace.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(MiconSpace.xs))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("노래나 가수를 검색해 보세요", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        inner()
                    },
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp, modifier = Modifier.size(MiconSpace.touchTarget)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("?", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                }
            }
        }
        IconButton(onClick = onToggleSearch, modifier = Modifier.size(MiconSpace.touchTarget)) {
            Icon(
                if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                contentDescription = if (searchOpen) "검색 닫기" else "검색",
                tint = scheme.onSurface,
            )
        }
        if (!searchOpen) {
            if (onLogin != null) {
                MiconButton("로그인", onLogin, kind = MiconButtonKind.Text)
            } else {
                IconButton(onClick = onSettings, modifier = Modifier.size(MiconSpace.touchTarget)) {
                    Icon(Icons.Outlined.Settings, "설정", Modifier.size(24.dp), tint = scheme.onSurface)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 데크 아래 정보
// ---------------------------------------------------------------------------

/**
 * 제목 · 가수 · 음역 배지. 비어 있어도 자리를 지켜 데크가 튀지 않는다.
 * 권장 키 배지와 곡 길이는 뺐다 — 그만큼 최소 높이도 같이 낮춰야 아래 데크가 위로 붙는다.
 */
@Composable
private fun NowInfo(item: RecommendedSong?, preview: RecommendPreview? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = MiconSpace.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item == null) return
        Text(item.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(item.song.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val lo = item.fit?.rangeFit?.songCoreLowMidi
        val hi = item.fit?.rangeFit?.songCoreHighMidi
        if (lo != null && hi != null) {
            Row(Modifier.padding(top = 2.dp)) { MiconBadge(text = NoteNames.rangeText(lo, hi)) }
        }
        val status = when {
            preview?.error != null -> preview.error
            preview?.loading == true -> "미리듣기 준비 중…"
            else -> null
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = if (preview?.error != null) scheme.tertiary else scheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------
// 고르기 — CD 올리기 전: 끌어 올리는 카드 / 올린 뒤: 눌러서 교체하는 디스크 줄
// ---------------------------------------------------------------------------

@Composable
private fun PickHead(title: String, hint: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MiconSpace.screenHorizontal),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 프로토타입 `.fan` + `.hintDrag`. 카드를 위로 끌어 올리면 데크에 올라간다 */
@Composable
private fun CardFan(header: String, songs: List<RecommendedSong>, onLoad: (RecommendedSong) -> Unit, onFloat: (FloatingCard?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PickHead(header, "위로 끌어 올려 담기")
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = MiconSpace.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(MiconSpace.sm),
        ) {
            items(songs.size, key = { songs[it].song.id }) { i ->
                val tilt by remember(listState, i) { derivedStateOf { fanTilt(listState, i) } }
                DragCard(songs[i], tilt = tilt, onLoad = { onLoad(songs[i]) }, onFloat = onFloat)
            }
        }
        Text(
            buildString { append("카드를 플레이어 쪽으로 끌어 올리면 CD가 올라갑니다") },
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MiconSpace.screenHorizontal),
        )
    }
}

/** 위로 끌리는 카드. 임계(80dp) 를 넘기면 올라가고, 못 넘기면 튕겨 돌아온다 */
/** 카드 가운데가 뷰포트 가운데에서 얼마나 떨어졌나 — -1(왼쪽 끝)..1(오른쪽 끝). 프로토타입 `.fan` 의 기울기 근거 */
private fun fanTilt(state: LazyListState, index: Int): Float {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
    val center = item.offset + item.size / 2f
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val half = info.viewportSize.width / 2f
    return if (half <= 0f) 0f else ((center - mid) / half).coerceIn(-1f, 1f)
}

@Composable
private fun DragCard(item: RecommendedSong, tilt: Float, onLoad: () -> Unit, onFloat: (FloatingCard?) -> Unit) {
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val float by rememberUpdatedState(onFloat)

    // 끌리는 동안 본체는 투명하게 두고 자리만 지킨다 — 실제로 보이는 건 화면 최상위의 FloatingDeckCard 다.
    // lift 는 **컴포지션에서** 읽어야 값이 바뀔 때 재구성이 걸리고 떠 있는 카드가 손가락을 따라온다.
    val liftValue = lift.value
    if (dragging) {
        SideEffect { float(FloatingCard(item, origin, liftValue, tilt, threshold)) }
    }

    Box(
        Modifier
            .width(96.dp)
            .onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = 14f * tilt
                translationY = abs(tilt) * 14.dp.toPx()
                alpha = if (dragging) 0f else 1f
            }
            .pointerInput(item.song.id) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onVerticalDrag = { _, dy -> scope.launch { lift.snapTo((lift.value + dy).coerceAtMost(0f)) } },
                    onDragEnd = {
                        if (lift.value < -threshold) {
                            onLoad()
                            scope.launch { lift.snapTo(0f); dragging = false; float(null) }
                        } else {
                            scope.launch {
                                lift.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                dragging = false
                                float(null)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { lift.animateTo(0f); dragging = false; float(null) } },
                )
            },
    ) { DeckCardFace(item) }
}

/** 카드 앞면. 목록 안의 카드와 떠 있는 카드가 같은 그림을 쓰도록 따로 뺐다 */
@Composable
private fun DeckCardFace(item: RecommendedSong) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(96.dp)
            .miconShadow(elevation = 8.dp, shape = MiconShape.field, alpha = 0.14f)
            .clip(MiconShape.field)
            .background(scheme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactDisc(title = item.song.title, artist = item.song.artist, imageUrl = item.song.imageUrl, size = 64.dp)
        Text(
            item.song.title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 32.dp),
        )
        Text(
            item.song.artist,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 프로토타입 `.discrow` — 올린 뒤. 누르면 바로 교체 */
@Composable
private fun DiscRow(songs: List<RecommendedSong>, onPick: (RecommendedSong) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PickHead("이 곡과 비슷한 곡", "누르면 바로 교체")
        LazyRow(
            contentPadding = PaddingValues(horizontal = MiconSpace.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(MiconSpace.sm),
        ) {
            items(songs, key = { it.song.id }) { item ->
                Column(
                    Modifier
                        .width(64.dp)
                        .clip(MiconShape.card)
                        .clickable { onPick(item) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactDisc(title = item.song.title, artist = item.song.artist, imageUrl = item.song.imageUrl, size = 60.dp)
                    Text(
                        item.song.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 첫 사용 코치 — 프로토타입 `.coach`. Dialog 라서 하단 탭 위에도 덮인다
// ---------------------------------------------------------------------------

@Composable
private fun CoachOverlay(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x7516103A))
                .clickable(onClick = onDismiss)
                .padding(start = 20.dp, end = 20.dp, bottom = 98.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
                    .miconShadow(elevation = 22.dp, shape = MiconShape.deck, alpha = 0.3f)
                    .clip(MiconShape.deck)
                    .background(scheme.surface)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CoachDemo()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("추천 사용법", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
                    Text("CD를 올려서 들어보세요", style = MaterialTheme.typography.titleMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    CoachStep(1, "아래 카드를 ", "위로 끌어 올리면", " CD가 플레이어에 올라가요")
                    CoachStep(2, "CD ", "가운데를 누르면", " 재생 / 일시정지")
                    CoachStep(3, "CD ", "양 끝을 누르면", " 이전 · 다음 곡")
                    CoachStep(4, "CD를 ", "아래로 끌어내리면", " 빼기")
                }
                MiconButton(text = "해볼게요", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CoachStep(n: Int, a: String, bold: String, b: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer) }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(a)
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = scheme.onSurface))
                append(bold); pop(); append(b)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 데모 — 카드가 올라가 사라지고 점선 자리에 CD 가 나타난다. 3.6초 반복 */
@Composable
private fun CoachDemo() {
    val scheme = MaterialTheme.colorScheme
    val t by rememberInfiniteTransition(label = "coach").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    // 카드: 0~8% 대기 → 46% 까지 38dp 상승 + 축소 → 56% 소멸. 디스크: 48~60% 등장 → 92% 유지 → 사라짐
    val cardP = ((t - 0.08f) / 0.38f).coerceIn(0f, 1f)
    val cardAlpha = if (t < 0.46f) 1f else (1f - (t - 0.46f) / 0.10f).coerceIn(0f, 1f)
    val discAlpha = when {
        t < 0.48f -> 0f
        t < 0.60f -> (t - 0.48f) / 0.12f
        t < 0.92f -> 1f
        else -> 1f - (t - 0.92f) / 0.08f
    }
    val discScale = 0.6f + 0.4f * discAlpha
    Box(
        Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(MiconShape.button)
            .background(scheme.background),
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Color(0xFFD3CDE8),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = discAlpha; scaleX = discScale; scaleY = discScale }
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(Color(0xFFEAD7F5), Color(0xFFD8E6FA), Color(0xFFDFF3EA), Color(0xFFFBEAD6), Color(0xFFF6D9E6), Color(0xFFEAD7F5)))),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(16.dp).clip(CircleShape).background(scheme.background)) }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .graphicsLayer {
                    translationY = -38.dp.toPx() * cardP
                    val s = 1f - 0.28f * cardP
                    scaleX = s; scaleY = s
                    alpha = cardAlpha
                }
                .size(width = 34.dp, height = 44.dp)
                .miconShadow(elevation = 6.dp, shape = RoundedCornerShape(7.dp), alpha = 0.3f)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFE8A87C), Color(0xFFF6D9BE)))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 0.dp)
                .graphicsLayer { translationX = 15.dp.toPx(); translationY = -38.dp.toPx() * cardP; alpha = if (t < 0.54f) 0.9f else 0f }
                .size(18.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.18f)),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(scheme.primary, style = Stroke(2.dp.toPx())) }
        }
    }
}

// ---------------------------------------------------------------------------
// 검색 · 빈 상태
// ---------------------------------------------------------------------------

@Composable
private fun SearchResults(state: RecommendViewModel.State, onRetry: () -> Unit, onPick: (RecommendedSong) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = scheme.primary) }
        state.error != null -> EmptyState(text = state.error!!, actionLabel = "다시 시도", onAction = onRetry)
        state.searchItems.isEmpty() -> EmptyState(text = "찾는 노래가 없어요")
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = MiconSpace.screenHorizontal),
        ) {
            state.filtered(state.searchItems).forEach { item ->
                SongRow(
                    title = item.song.title,
                    artist = item.song.artist,
                    imageUrl = item.song.imageUrl,
                    keyLabel = item.fit?.recommendedKey?.let(NoteNames::keyShiftText),
                    onClick = { onPick(item) },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(MiconSpace.navBarContentPadding))
        }
    }
}

@Composable
private fun EmptyState(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = MiconSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(MiconSpace.md))
            MiconButton(text = actionLabel, onClick = onAction, kind = MiconButtonKind.Outline)
        }
        if (extra != null) { Spacer(Modifier.height(MiconSpace.md)); extra() }
    }
}

/** 랙 칩 라벨 — 프로토타입 문구. 서버 title 은 모르는 key 에만 쓴다 */
private fun rackLabel(shelf: RecommendShelf): String = when (shelf.key) {
    ShelfKeys.VOICE -> "내 음색에 딱"
    ShelfKeys.ORIGINAL_KEY -> "내 음역대"
    ShelfKeys.FAVORITE_ARTISTS -> "좋아하는 가수"
    ShelfKeys.DISCOVERY -> "새로운 발견"
    else -> shelf.title
}
