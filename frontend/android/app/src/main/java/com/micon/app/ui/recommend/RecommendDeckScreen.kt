package com.micon.app.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.micon.app.data.onboarding.VoiceOnboardingStore
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconFilterChip
import com.micon.app.ui.design.atoms.MiconMark
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.CdPlayer
import com.micon.app.ui.design.tokens.MiconDesignTheme
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.onboarding.VoiceOnboardingEntry
import com.micon.app.ui.onboarding.VoiceOnboardingEntryViewModel
import com.micon.app.ui.rememberAppContainer

/**
 * 추천 탭 — 포터블 CD 플레이어. 프로토타입 `#recommend` 화면.
 *
 * 흐름: 위 랙 칩으로 섹션을 고른다 → 아래 디스크 줄에서 곡을 누르면 데크에 CD 가 올라간다 →
 * ⏮ ⏭ 로 같은 섹션 안에서 갈아끼운다 → CD 가운데나 `이 곡 자세히 보기` 로 곡 상세.
 *
 * 프로토타입의 "카드를 위로 끌어 올려 담기" 드래그는 아직 없다 — 지금은 탭으로 올린다.
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

    // 목소리 분석 온보딩 진입 — 기존 RecommendScreen 과 같은 배선
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

    // --- 화면 상태: 고른 섹션, 데크 위의 곡 ---
    var shelfIndex by rememberSaveable { mutableIntStateOf(0) }
    var loadedId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val shelves = state.shelves
    val shelf = shelves.getOrNull(shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0)))
    val items = shelf?.let { state.filtered(it.items) } ?: emptyList()
    val loadedIndex = items.indexOfFirst { it.song.id == loadedId }
    val loaded = items.getOrNull(loadedIndex)

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

    MiconDesignTheme {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
            TopBar(
                searchOpen = searchOpen,
                query = state.query,
                onQuery = vm::setQuery,
                onToggleSearch = { if (searchOpen) vm.setQuery(""); searchOpen = !searchOpen },
            )

            when {
                state.query.isNotBlank() -> SearchResults(state, onRetry = { vm.setQuery(state.query) }, onPick = ::open)

                state.loading && shelves.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = scheme.primary) }

                state.error != null && shelves.isEmpty() ->
                    EmptyState(text = state.error!!, actionLabel = "다시 불러오기", onAction = vm::load)

                state.note == RecommendShelves.NOTE_ANALYSIS_REQUIRED ->
                    EmptyState(text = state.note!!) {
                        VoiceOnboardingEntry(entryState, entry::open, entry::resume, entry::startNew, entry::dismiss)
                    }

                shelves.isEmpty() -> {
                    val serverEmpty = state.note?.startsWith(RecommendShelves.NOTE_EMPTY) == true
                    EmptyState(
                        text = state.note ?: "조건에 맞는 추천곡이 없어요",
                        actionLabel = if (serverEmpty) "추천 다시 만들기" else "다시 불러오기",
                        onAction = { if (serverEmpty) vm.retry() else vm.load() },
                    )
                }

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 랙 — 섹션 칩. 선택은 primary 채움
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
                        onDiscClick = { loaded?.let(::open) },
                        onPrev = { items.getOrNull(loadedIndex - 1)?.let { loadedId = it.song.id } },
                        onNext = { items.getOrNull(loadedIndex + 1)?.let { loadedId = it.song.id } },
                        modifier = Modifier.padding(horizontal = MiconSpace.screenHorizontal),
                    )

                    NowInfo(loaded)

                    Picker(
                        header = if (loaded == null) shelf?.let(::rackLabel).orEmpty() else "이 곡과 비슷한 곡",
                        hint = if (loaded == null) "눌러서 플레이어에 올리기" else "누르면 바로 교체",
                        songs = items.filter { it.song.id != loadedId },
                        onPick = { loadedId = it.song.id },
                    )

                    MiconButton(
                        text = "이 곡 자세히 보기",
                        onClick = { loaded?.let(::open) },
                        enabled = loaded != null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MiconSpace.screenHorizontal),
                    )

                    Spacer(Modifier.height(MiconSpace.navBarContentPadding))
                }
            }
        }
    }
}

/** 마크 + `추천` · 검색. 검색을 열면 제목 자리가 입력창이 된다 */
@Composable
private fun TopBar(searchOpen: Boolean, query: String, onQuery: (String) -> Unit, onToggleSearch: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = MiconSpace.topBarMinHeight).padding(horizontal = MiconSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Row(Modifier.weight(1f).padding(start = MiconSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                MiconMark()
                Spacer(Modifier.width(10.dp))
                Text("추천", style = MaterialTheme.typography.titleMedium)
            }
        }
        IconButton(onClick = onToggleSearch, modifier = Modifier.size(MiconSpace.touchTarget)) {
            Icon(
                if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                contentDescription = if (searchOpen) "검색 닫기" else "검색",
                tint = scheme.onSurface,
            )
        }
    }
}

/** 데크 아래 — 제목 · 가수 · 키/음역 배지 · 길이. 비어 있어도 자리를 지켜 데크가 튀지 않는다 */
@Composable
private fun NowInfo(item: RecommendedSong?) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(horizontal = MiconSpace.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item == null) return
        Text(item.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(item.song.artist, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.fit?.recommendedKey?.let { key ->
                MiconBadge(
                    text = if (key == 0) "원래 키로 편안" else NoteNames.keyShiftText(key) + " 권장",
                    tone = if (key == 0) MiconChipTone.Success else MiconChipTone.Warning,
                )
            }
            val lo = item.fit?.rangeFit?.songCoreLowMidi
            val hi = item.fit?.rangeFit?.songCoreHighMidi
            if (lo != null && hi != null) MiconBadge(text = NoteNames.rangeText(lo, hi))
        }
        item.song.durationMs?.let { ms ->
            val s = ms / 1000
            Text(
                "%d:%02d".format(s / 60, s % 60),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MiconMonoFamily),
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 디스크 줄 — 누르면 데크에 올라간다 */
@Composable
private fun Picker(header: String, hint: String, songs: List<RecommendedSong>, onPick: (RecommendedSong) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MiconSpace.screenHorizontal),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(header, style = MaterialTheme.typography.labelLarge)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
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
            Spacer(Modifier.height(MiconSpace.navBarContentPadding))
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
