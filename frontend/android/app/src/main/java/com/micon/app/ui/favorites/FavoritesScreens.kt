package com.micon.app.ui.favorites

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import com.micon.app.ui.design.organisms.tabBarContentPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.SongSummary
import com.micon.app.data.backend.BackendRequestException
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.favorites.Setlist
import com.micon.app.data.favorites.SetlistDetail
import com.micon.app.data.favorites.SetlistRepository
import com.micon.app.data.favorites.SetlistSong
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.common.HeartButton
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.songlist.SongCatalogContent
import com.micon.app.ui.songlist.SongListViewModel
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 찜 탭. 데이터: 백엔드 `GET /users/me/favorites`(찜 곡), `GET /setlists`(세트리스트).
 * 비회원은 NavHost 가 로그인 안내를 보여 주므로 여기 오지 않는다.
 */
class FavoritesViewModel(
    private val favorites: FavoriteRepository,
    private val setlists: SetlistRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val songs: List<SongSummary> = emptyList(),
        val setlists: List<Setlist> = emptyList(),
        val busy: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val songs = favorites.refresh()
                val lists = setlists.list()
                ensureActive()
                // 곡마다 /fit 을 부르던 권장 키 배지는 없앴다 — 찜 목록에서 N+1 요청이 사라졌다.
                _state.update { it.copy(loading = false, songs = songs, setlists = lists) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describeBackendError(e, "찜 목록을 불러오지 못했어요.")) }
            }
        }
    }

    /** 하트: 낙관적으로 캐시가 먼저 바뀌고, 해제되면 목록에서도 바로 뺀다. 실패하면 저장소가 되돌리고 토스트로 알린다. */
    fun toggleLike(songId: String) {
        viewModelScope.launch {
            try {
                if (!favorites.toggle(songId)) _state.update { it.copy(songs = it.songs.filterNot { s -> s.id == songId }) }
            } catch (e: Exception) {
                _state.update { it.copy(toast = describeBackendError(e, "찜을 바꾸지 못했어요.")) }
            }
        }
    }

    fun createSetlist(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || _state.value.busy) return
        if (_state.value.setlists.any { it.name.equals(normalized, ignoreCase = true) }) {
            _state.update { it.copy(toast = "이미 사용중인 이름입니다") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                setlists.create(normalized)
                _state.update { it.copy(setlists = setlists.list(), busy = false, toast = "세트리스트를 만들었어요.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = describeBackendError(e, "세트리스트를 만들지 못했어요.")) }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}

private enum class Tab(val label: String) { FAVORITES("찜"), SETLIST("세트리스트") }

/** Figma "노래 찜 목록" / "노래 찜 목록 - 세트리스트" */
@Composable
fun FavoritesScreen(onSongClick: (String) -> Unit, onSetlistClick: (Long) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: FavoritesViewModel = viewModel(factory = viewModelFactory {
        initializer { FavoritesViewModel(container.favorites, container.setlists) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume(vm::load)
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.FAVORITES) }
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() } }
    if (showCreate) {
        NameDialog(
            "새 세트리스트",
            initial = "",
            confirmLabel = "생성",
            unavailableNames = state.setlists.mapTo(mutableSetOf()) { it.name },
            onDismiss = { showCreate = false },
        ) { showCreate = false; vm.createSetlist(it) }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC))) {
        MiconHeader(onSettings = onSettings)
        MiconSearchBar(
            query,
            { query = it },
            Modifier.padding(horizontal = 20.dp),
            placeholder = if (tab == Tab.SETLIST) "세트리스트 이름을 검색해 보세요" else "노래나 가수를 검색해 보세요",
            prototypeStyle = true,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tab.entries.forEach { t -> FavoritesFilterChip(t.label, selected = tab == t, onClick = { tab = t }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (tab) { Tab.SETLIST -> "${state.setlists.size}개 리스트"; Tab.FAVORITES -> "${state.songs.size}곡" },
                fontSize = 12.sp, color = Grey, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = GreyLight)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null -> RetryBlock(state.error!!, vm::load)
            tab == Tab.SETLIST -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = tabBarContentPadding(horizontal = MiconSpace.screenHorizontal),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !state.busy) { showCreate = true }.padding(horizontal = 12.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, "새 리스트", Modifier.size(32.dp), tint = Purple)
                        Spacer(Modifier.width(12.dp))
                        Text("새 세트리스트", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                    HorizontalDivider(color = Color(0xFFE1DFE8))
                    Spacer(Modifier.height(6.dp))
                }
                val visibleSetlists = state.setlists.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                items(visibleSetlists, key = { it.id }) { sl ->
                    SongRow(title = sl.name, artist = "${sl.songCount}곡", imageUrl = null, onClick = { onSetlistClick(sl.id) })
                }
                if (visibleSetlists.isEmpty()) item {
                    Text(if (query.isBlank()) "세트리스트가 없어요. 위에서 새 리스트를 만들어 보세요." else "검색 결과가 없습니다.", Modifier.padding(vertical = 24.dp), color = Grey)
                }
            }
            else -> {
                val songs = state.songs
                    .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = tabBarContentPadding(horizontal = MiconSpace.screenHorizontal),
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                            trailing = { HeartButton(liked = true) { vm.toggleLike(song.id) } },
                        )
                    }
                    if (songs.isEmpty()) item {
                        Text(
                            if (query.isBlank()) "찜한 곡이 없어요. 연습 탭에서 하트를 눌러 보세요." else "검색 결과가 없습니다.",
                            Modifier.padding(vertical = 24.dp), color = Grey,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

/** 세트리스트 세부. 데이터: `GET /setlists/{id}`. 이름 변경·삭제, 전체 곡 검색·추가, 곡 제거를 지원한다. */
class SetlistViewModel(
    private val setlists: SetlistRepository,
    private val setlistId: Long,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val detail: SetlistDetail? = null,
        val editing: Boolean = false,
        val busy: Boolean = false,
        val deleted: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val detail = setlists.detail(setlistId)
                _state.update { it.copy(loading = false, detail = detail) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describeBackendError(e, "세트리스트를 불러오지 못했어요.")) }
            }
        }
    }

    fun toggleEdit() = _state.update { it.copy(editing = !it.editing) }

    fun rename(name: String) = run("이름을 바꾸지 못했어요.") {
        val renamed = setlists.rename(setlistId, name)
        _state.update { s -> s.copy(detail = s.detail?.copy(name = renamed.name), toast = "이름을 바꿨어요.") }
    }

    fun delete() = run("삭제하지 못했어요.") {
        setlists.delete(setlistId)
        _state.update { it.copy(deleted = true) }
    }

    fun removeSong(songId: String) = run("곡을 빼지 못했어요.") {
        _state.update { it.copy(detail = setlists.removeSong(setlistId, songId)) }
    }

    fun addSong(songId: String) = run("곡을 추가하지 못했어요.") {
        val detail = setlists.addSong(setlistId, songId)
        _state.update { it.copy(detail = detail) }
    }

    fun moveSong(songId: String, targetSongId: String) {
        if (_state.value.busy) return
        _state.update { state ->
            val detail = state.detail ?: return@update state
            state.copy(detail = detail.copy(songs = moveSetlistSong(detail.songs, songId, targetSongId)))
        }
    }

    fun saveSongOrder() = run("곡 순서를 바꾸지 못했어요.") {
        val detail = _state.value.detail ?: return@run
        try {
            _state.update { it.copy(detail = setlists.reorderSongs(setlistId, detail.songs.map(SetlistSong::songId), detail.updatedAt)) }
        } catch (e: Exception) {
            runCatching { setlists.detail(setlistId) }.getOrNull()?.let { latest ->
                _state.update { it.copy(detail = latest) }
            }
            throw e
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    private fun run(failure: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                // 배포 전 구버전 서버가 반환하는 폐기된 찜 제한 문구는 사용자에게 노출하지 않는다.
                val message = if (e is BackendRequestException && e.code == "SONG_NOT_FAVORITED") {
                    failure
                } else {
                    describeBackendError(e, failure)
                }
                _state.update { it.copy(toast = message) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

}

internal fun moveSetlistSong(songs: List<SetlistSong>, songId: String, targetSongId: String): List<SetlistSong> {
    val from = songs.indexOfFirst { it.songId == songId }
    val to = songs.indexOfFirst { it.songId == targetSongId }
    if (from < 0 || to < 0 || from == to) return songs
    return songs.toMutableList().apply { add(to, removeAt(from)) }.mapIndexed { index, song -> song.copy(position = index) }
}

/** Figma "노래 찜 목록 - 세트리스트 세부" */
@Composable
fun SetlistScreen(setlistId: Long, onBack: () -> Unit, onSongClick: (String) -> Unit) {
    val container = rememberAppContainer()
    val vm: SetlistViewModel = viewModel(key = "setlist-$setlistId", factory = viewModelFactory {
        initializer { SetlistViewModel(container.setlists, setlistId) }
    })
    // 연습 탭과 동일한 ViewModel을 화면 진입과 동시에 준비해 곡 추가 창의 첫 로딩을 줄인다.
    val pickerVm: SongListViewModel = viewModel(key = "setlist-picker-$setlistId", factory = viewModelFactory {
        initializer {
            SongListViewModel(
                container.songs,
                container.recommend,
                NetworkPreferenceRepository(container.backend.retrofit.create(PreferenceApi::class.java)),
            )
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val pickerState by pickerVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var draggedSongId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var autoScrollDirection by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() } }
    LaunchedEffect(state.deleted) { if (state.deleted) { Toast.makeText(context, "세트리스트를 삭제했어요.", Toast.LENGTH_SHORT).show(); onBack() } }
    LaunchedEffect(draggedSongId, autoScrollDirection) {
        while (draggedSongId != null && autoScrollDirection != 0) {
            val dragged = draggedSongId ?: break
            // 한 프레임에 너무 멀리 움직이면 곡이 서로 건너뛰는 것처럼 보여서,
            // 작은 거리로 연속 이동시킨다. 드래그한 행은 같은 화면 위치를 유지한다.
            val scrolled = listState.scrollBy(autoScrollDirection * 6f)
            if (scrolled == 0f) {
                autoScrollDirection = 0
                break
            }
            // 목록이 스크롤된 만큼 선택 곡도 손가락 아래에 계속 남겨 둔다.
            dragOffset += scrolled
            val songItems = listState.layoutInfo.visibleItemsInfo.filter { it.key is String }
            val currentIndex = songItems.indexOfFirst { it.key == dragged }
            val current = songItems.getOrNull(currentIndex)
            val target = songItems.getOrNull(currentIndex + autoScrollDirection)
            if (current != null && target != null) {
                val draggedCenter = current.offset + current.size / 2 + dragOffset
                val targetCenter = target.offset + target.size / 2
                val crossedTargetCenter = if (autoScrollDirection > 0) draggedCenter >= targetCenter else draggedCenter <= targetCenter
                val targetId = target.key as? String
                if (crossedTargetCenter && targetId != null) {
                    // 선택 행은 손가락 아래에 남기고, 빈자리를 다른 곡이 채운다.
                    dragOffset -= target.offset - current.offset
                    vm.moveSong(dragged, targetId)
                }
            }
            delay(16)
        }
    }

    val detail = state.detail
    if (showRename && detail != null) {
        NameDialog("이름 변경", initial = detail.name, confirmLabel = "저장", onDismiss = { showRename = false }) { showRename = false; vm.rename(it) }
    }
    if (showDelete && detail != null) {
        ConfirmDialog(
            title = "세트리스트를 삭제할까요?", text = "'${detail.name}' 리스트가 지워져요.",
            confirmLabel = "삭제", onDismiss = { showDelete = false }, onConfirm = { showDelete = false; vm.delete() },
        )
    }
    if (showAdd) {
        AddSongDialog(
            state = pickerState,
            excludedIds = state.detail?.songs?.map { it.songId }?.toSet().orEmpty(),
            onQueryChange = pickerVm::setQuery,
            onGenreChange = pickerVm::setGenre,
            onRefresh = pickerVm::refresh,
            onLoadMore = pickerVm::loadMore,
            onDismiss = { showAdd = false },
            onAdd = vm::addSong,
        )
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC))) {
        BackHeader("나의 세트리스트", onBack) {
            Box {
                IconButton(onClick = { menu = true }, enabled = detail != null) { Icon(Icons.Filled.MoreHoriz, "더보기", tint = Ink) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.width(180.dp).background(Color(0xFFF7F5FC), RoundedCornerShape(16.dp)),
                    containerColor = Color(0xFFF7F5FC),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text("이름 변경", color = Ink, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = Purple) },
                        onClick = { menu = false; showRename = true },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Color(0xFFE1DFE8))
                    DropdownMenuItem(
                        text = { Text("세트리스트 삭제", color = Red, fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Red) },
                        onClick = { menu = false; showDelete = true },
                    )
                }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            detail == null -> RetryBlock(state.error ?: "세트리스트를 찾을 수 없습니다.", vm::load)
            else -> {
                Surface(color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactDisc(title = detail.name, imageUrl = detail.songs.firstOrNull()?.imageUrl, size = 120.dp)
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(detail.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Spacer(Modifier.height(8.dp))
                            Text("총 ${detail.songs.size}곡", fontSize = 15.sp, color = Grey)
                        }
                        IconButton(onClick = vm::toggleEdit) {
                            Icon(if (state.editing) Icons.Filled.Check else Icons.Outlined.Edit, if (state.editing) "편집 끝" else "편집", tint = if (state.editing) Purple else Ink)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(8.dp).background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent),
                        ),
                    ),
                )
                LazyColumn(
                    Modifier.fillMaxSize().background(Color(0xFFF7F5FC)),
                    state = listState,
                    contentPadding = tabBarContentPadding(horizontal = MiconSpace.screenHorizontal),
                ) {
                    if (state.editing) item {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.busy) { showAdd = true }.padding(horizontal = 12.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, "곡 추가", Modifier.size(32.dp), tint = Purple)
                            Spacer(Modifier.width(12.dp))
                            Text("곡 검색해서 추가", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                        HorizontalDivider(color = Color(0xFFE1DFE8))
                        Spacer(Modifier.height(6.dp))
                    }
                    items(detail.songs, key = { it.songId }) { song ->
                        val dragging = draggedSongId == song.songId
                        Row(
                            Modifier.fillMaxWidth()
                                // 멜론처럼 선택 행은 손가락을 따라 떠 있고, 다른 곡이
                                // 중앙 교체 시점에 빈자리를 부드럽게 메운다.
                                .then(if (dragging) Modifier else Modifier.animateItem(placementSpec = tween(180, easing = FastOutSlowInEasing)))
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragOffset else 0f
                                }
                                .background(if (dragging) Color(0xFFF2F2F2) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.editing) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(48.dp)
                                        .background(if (dragging) Purple.copy(alpha = 0.14f) else Color.Transparent, CircleShape)
                                        .pointerInput(state.busy, song.songId) {
                                        if (state.busy) return@pointerInput
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedSongId = song.songId
                                                dragOffset = 0f
                                                autoScrollDirection = 0
                                            },
                                            onDragCancel = {
                                                draggedSongId = null
                                                dragOffset = 0f
                                                autoScrollDirection = 0
                                            },
                                            onDragEnd = {
                                                draggedSongId = null
                                                dragOffset = 0f
                                                autoScrollDirection = 0
                                                vm.saveSongOrder()
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val dragged = draggedSongId ?: return@detectDragGesturesAfterLongPress
                                                val songItems = listState.layoutInfo.visibleItemsInfo.filter { it.key is String }
                                                val currentIndex = songItems.indexOfFirst { it.key == dragged }
                                                val current = songItems.getOrNull(currentIndex) ?: return@detectDragGesturesAfterLongPress
                                                // 드래그 중인 카드가 화면 가장자리 가까이에 왔을 때만
                                                // 자동 스크롤한다. 화면에 남은 곡 개수가 아니라 실제
                                                // 카드 위치를 기준으로 하므로 손가락과 목록이 따로 놀지 않는다.
                                                val cardTop = current.offset + dragOffset
                                                val cardBottom = cardTop + current.size
                                                val edgeInset = 72f
                                                autoScrollDirection = when {
                                                    amount.y > 0f && cardBottom >= listState.layoutInfo.viewportEndOffset - edgeInset -> 1
                                                    amount.y < 0f && cardTop <= listState.layoutInfo.viewportStartOffset + edgeInset -> -1
                                                    else -> 0
                                                }
                                                val target = when {
                                                    dragOffset > 0f -> songItems.getOrNull(currentIndex + 1)
                                                    dragOffset < 0f -> songItems.getOrNull(currentIndex - 1)
                                                    else -> null
                                                } ?: return@detectDragGesturesAfterLongPress
                                                val draggedCenter = current.offset + current.size / 2 + dragOffset
                                                val targetCenter = target.offset + target.size / 2
                                                val crossedTargetCenter = if (dragOffset > 0f) {
                                                    draggedCenter >= targetCenter
                                                } else {
                                                    draggedCenter <= targetCenter
                                                }
                                                val targetId = target.key as? String
                                                if (crossedTargetCenter && targetId != null) {
                                                    // 선택 행은 손가락 아래에 유지되고,
                                                    // 다른 곡만 기존 자리로 자연스럽게 이동한다.
                                                    dragOffset -= target.offset - current.offset
                                                    vm.moveSong(dragged, targetId)
                                                }
                                            },
                                        )
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = "길게 눌러 곡 순서 변경",
                                        tint = if (dragging) Purple else Grey,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                VerticalDivider(Modifier.height(32.dp), thickness = 1.dp, color = GreyLight)
                                Spacer(Modifier.width(4.dp))
                            }
                            SongRow(
                                title = song.title, artist = song.artist, imageUrl = song.imageUrl,
                                onClick = { onSongClick(song.songId) }, modifier = Modifier.weight(1f),
                                trailing = if (state.editing) ({
                                    IconButton(onClick = { vm.removeSong(song.songId) }, enabled = !state.busy) { Icon(Icons.Outlined.Delete, "리스트에서 빼기", tint = Red) }
                                }) else null,
                            )
                        }
                    }
                    if (detail.songs.isEmpty()) item {
                        Text("아직 곡이 없어요. 편집(연필)을 눌러 곡을 추가해 보세요.", Modifier.padding(vertical = 24.dp), color = Grey)
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    unavailableNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val normalizedName = name.trim()
    val duplicate = normalizedName.isNotEmpty() && unavailableNames.any { it.equals(normalizedName, ignoreCase = true) }
    val valid = normalizedName.isNotEmpty() && !duplicate
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(start = 22.dp, end = 14.dp, top = 12.dp, bottom = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Grey)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                if (name.isEmpty()) {
                    Text("예) 회식용, 잔잔한 노래", fontSize = 16.sp, color = Grey)
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(SetlistRepository.NAME_MAX) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = Ink),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            HorizontalDivider(color = if (name.isNotBlank()) Purple else GreyLight, thickness = 1.dp, modifier = Modifier.padding(end = 8.dp))
            if (duplicate) {
                Text(
                    "이미 사용중인 이름입니다",
                    color = Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = { if (valid) onConfirm(normalizedName) },
                modifier = Modifier.align(Alignment.CenterHorizontally).width(120.dp).height(46.dp),
                shape = RoundedCornerShape(23.dp),
                color = if (valid) Purple else GreyLight,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(confirmLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = Grey) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Red, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Grey) } },
    )
}

/** 전체 곡 카탈로그를 검색해 세트리스트에 없는 곡을 추가한다. */
@Composable
private fun AddSongDialog(
    state: SongListViewModel.State,
    excludedIds: Set<String>,
    onQueryChange: (String) -> Unit,
    onGenreChange: (Long?) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Color.White, RoundedCornerShape(22.dp))
                .padding(top = 8.dp, bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("곡 추가", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", tint = Grey)
                }
            }
            SongCatalogContent(
                state = state,
                onQueryChange = onQueryChange,
                onGenreChange = onGenreChange,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onSongClick = onAdd,
                excludedIds = excludedIds,
                searchPlaceholder = "검색",
                modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp).padding(horizontal = 8.dp),
            )
        }
    }
}
