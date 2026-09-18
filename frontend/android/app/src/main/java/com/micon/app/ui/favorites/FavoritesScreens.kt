package com.micon.app.ui.favorites

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.micon.app.data.SongSummary
import com.micon.app.data.favorites.FavoriteRepository
import com.micon.app.data.favorites.Setlist
import com.micon.app.data.favorites.SetlistDetail
import com.micon.app.data.favorites.SetlistRepository
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.rememberAppContainer
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

/**
 * 찜 탭. 데이터: 백엔드 `GET /users/me/favorites`(찜 곡), `GET /setlists`(세트리스트), `GET /practices`(연습한 곡 → "연습 중" 탭).
 * 비회원은 NavHost 가 로그인 안내를 보여 주므로 여기 오지 않는다.
 */
class FavoritesViewModel(
    private val favorites: FavoriteRepository,
    private val setlists: SetlistRepository,
    private val practices: PracticeRepository,
    private val recommend: RecommendRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val songs: List<SongSummary> = emptyList(),
        /** 연습 기록이 있는 곡 id — "연습 중" 탭 */
        val practicedIds: Set<String> = emptySet(),
        val setlists: List<Setlist> = emptyList(),
        val keys: Map<String, Int?> = emptyMap(),
        val busy: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val songs = favorites.refresh()
                val lists = setlists.list()
                val practiced = runCatching { practices.history(page = 0, size = 50).items.map { it.songId }.toSet() }.getOrDefault(emptySet())
                _state.update { it.copy(loading = false, songs = songs, setlists = lists, practicedIds = practiced) }
                val keys = songs.associate { s -> s.id to runCatching { recommend.fit(s.id)?.recommendedKey }.getOrNull() }
                _state.update { it.copy(keys = keys) }
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
        if (name.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                setlists.create(name)
                _state.update { it.copy(setlists = setlists.list(), busy = false, toast = "세트리스트를 만들었어요.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, toast = describeBackendError(e, "세트리스트를 만들지 못했어요.")) }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}

private enum class Tab(val label: String) { ALL("전체"), PRACTICING("연습 중"), SETLIST("내 세트리스트") }

/** Figma "노래 찜 목록" / "노래 찜 목록 - 세트리스트" */
@Composable
fun FavoritesScreen(onSongClick: (String) -> Unit, onSetlistClick: (Long) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: FavoritesViewModel = viewModel(factory = viewModelFactory {
        initializer { FavoritesViewModel(container.favorites, container.setlists, container.practices, container.recommend) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.ALL) }
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() } }
    if (showCreate) {
        NameDialog("새 세트리스트", initial = "", confirmLabel = "만들기", onDismiss = { showCreate = false }) { showCreate = false; vm.createSetlist(it) }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        MiconSearchBar(query, { query = it }, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tab.entries.forEach { t -> PillChip(t.label, selected = tab == t, onClick = { tab = t }, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Menu, null, Modifier.size(22.dp), tint = Ink)
            Spacer(Modifier.width(10.dp))
            Text(
                when (tab) { Tab.SETLIST -> "${state.setlists.size}개 리스트"; Tab.PRACTICING -> "연습 기록이 있는 찜 곡"; Tab.ALL -> "${state.songs.size}곡" },
                fontSize = 15.sp, color = Grey, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = GreyLight)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null -> RetryBlock(state.error!!, vm::load)
            tab == Tab.SETLIST -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !state.busy) { showCreate = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(76.dp).background(Color(0xFF15161C), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(50.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, "새 리스트", Modifier.size(28.dp), tint = Ink)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("새로운 리스트", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                }
                items(state.setlists, key = { it.id }) { sl ->
                    SongRow(title = sl.name, artist = "${sl.songCount}곡", imageUrl = null, onClick = { onSetlistClick(sl.id) })
                }
                if (state.setlists.isEmpty()) item { Text("세트리스트가 없어요. 위에서 새 리스트를 만들어 보세요.", Modifier.padding(24.dp), color = Grey) }
            }
            else -> {
                val songs = state.songs
                    .filter { tab == Tab.ALL || it.id in state.practicedIds }
                    .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                            keyBadge = state.keys[song.id], liked = true, onLike = { vm.toggleLike(song.id) },
                        )
                    }
                    if (songs.isEmpty()) item {
                        Text(
                            if (tab == Tab.PRACTICING) "연습한 찜 곡이 없어요. 찜한 곡을 연습 탭에서 불러 보세요." else "찜한 곡이 없어요. 연습 탭에서 하트를 눌러 보세요.",
                            Modifier.padding(24.dp), color = Grey,
                        )
                    }
                }
            }
        }
    }
}

/** 세트리스트 세부. 데이터: `GET /setlists/{id}`. 이름 변경·삭제(더보기 메뉴), 곡 추가(찜한 곡 중)·제거(편집 모드). */
class SetlistViewModel(
    private val setlists: SetlistRepository,
    private val favorites: FavoriteRepository,
    private val recommend: RecommendRepository,
    private val setlistId: Long,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val detail: SetlistDetail? = null,
        val keys: Map<String, Int?> = emptyMap(),
        val editing: Boolean = false,
        /** 곡 추가 다이얼로그 후보(찜했지만 아직 없는 곡). null 이면 닫힘 */
        val candidates: List<SongSummary>? = null,
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
                val keys = detail.songs.associate { s -> s.songId to runCatching { recommend.fit(s.songId)?.recommendedKey }.getOrNull() }
                _state.update { it.copy(keys = keys) }
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

    fun addSong(songId: String) = run("곡을 넣지 못했어요.") {
        val detail = setlists.addSong(setlistId, songId)
        _state.update { it.copy(detail = detail, candidates = it.candidates?.filterNot { c -> c.id == songId }) }
    }

    /** 찜한 곡 중 이 리스트에 없는 곡을 후보로 연다(서버 규칙: 찜한 곡만 추가 가능). */
    fun openAdd() = run("찜 목록을 불러오지 못했어요.") {
        val inList = _state.value.detail?.songs?.map { it.songId }?.toSet() ?: emptySet()
        _state.update { it.copy(candidates = favorites.refresh().filterNot { s -> s.id in inList }) }
    }

    fun closeAdd() = _state.update { it.copy(candidates = null) }

    fun toggleLike(songId: String) {
        viewModelScope.launch {
            runCatching { favorites.toggle(songId) }.onFailure { e -> _state.update { it.copy(toast = describeBackendError(e, "찜을 바꾸지 못했어요.")) } }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    private fun run(failure: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { block() } catch (e: Exception) { _state.update { it.copy(toast = describeBackendError(e, failure)) } }
            _state.update { it.copy(busy = false) }
        }
    }
}

/** Figma "노래 찜 목록 - 세트리스트 세부" */
@Composable
fun SetlistScreen(setlistId: Long, onBack: () -> Unit, onSongClick: (String) -> Unit) {
    val container = rememberAppContainer()
    val vm: SetlistViewModel = viewModel(key = "setlist-$setlistId", factory = viewModelFactory {
        initializer { SetlistViewModel(container.setlists, container.favorites, container.recommend, setlistId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val liked by container.favorites.likedIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() } }
    LaunchedEffect(state.deleted) { if (state.deleted) { Toast.makeText(context, "세트리스트를 삭제했어요.", Toast.LENGTH_SHORT).show(); onBack() } }

    val detail = state.detail
    if (showRename && detail != null) {
        NameDialog("이름 변경", initial = detail.name, confirmLabel = "저장", onDismiss = { showRename = false }) { showRename = false; vm.rename(it) }
    }
    if (showDelete && detail != null) {
        ConfirmDialog(
            title = "세트리스트를 삭제할까요?", text = "'${detail.name}' 리스트가 지워져요. 찜한 곡은 그대로 남아요.",
            confirmLabel = "삭제", onDismiss = { showDelete = false }, onConfirm = { showDelete = false; vm.delete() },
        )
    }
    state.candidates?.let { candidates -> AddSongDialog(candidates, onDismiss = vm::closeAdd, onAdd = vm::addSong) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("나의 세트리스트", onBack) {
            Box {
                IconButton(onClick = { menu = true }, enabled = detail != null) { Icon(Icons.Filled.MoreHoriz, "더보기", tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; showRename = true })
                    DropdownMenuItem(text = { Text("삭제", color = Red) }, onClick = { menu = false; showDelete = true })
                }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            detail == null -> RetryBlock(state.error ?: "세트리스트를 찾을 수 없습니다.", vm::load)
            else -> {
                Surface(color = Color.White, shadowElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        VinylArt(detail.songs.firstOrNull()?.imageUrl, 120.dp, fallbackText = detail.name)
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
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    if (state.editing) item {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.busy) { vm.openAdd() }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(56.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "곡 추가", tint = Purple) }
                            Spacer(Modifier.width(16.dp))
                            Text("찜한 곡에서 추가", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Purple)
                        }
                    }
                    items(detail.songs, key = { it.songId }) { song ->
                        SongRow(
                            title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.songId) },
                            keyBadge = state.keys[song.songId], liked = song.songId in liked, onLike = { vm.toggleLike(song.songId) },
                            trailing = if (state.editing) ({
                                IconButton(onClick = { vm.removeSong(song.songId) }, enabled = !state.busy) { Icon(Icons.Outlined.Delete, "리스트에서 빼기", tint = Red) }
                            }) else null,
                        )
                    }
                    if (detail.songs.isEmpty()) item {
                        Text("아직 곡이 없어요. 편집(연필)을 눌러 찜한 곡을 넣어 보세요.", Modifier.padding(24.dp), color = Grey)
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = { MiconTextField("이름", name, { name = it.take(SetlistRepository.NAME_MAX) }, "예) 회식용, 잔잔한 노래") },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel, color = Purple, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Grey) } },
    )
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

/** 찜한 곡 중 리스트에 없는 곡. 누르면 바로 추가되고 목록에서 사라진다. */
@Composable
private fun AddSongDialog(candidates: List<SongSummary>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("찜한 곡에서 추가", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            if (candidates.isEmpty()) Text("추가할 수 있는 찜 곡이 없어요. 연습 탭에서 곡을 먼저 찜해 주세요.", color = Grey)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(candidates, key = { it.id }) { song ->
                    SongRow(title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onAdd(song.id) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", color = Purple, fontWeight = FontWeight.SemiBold) } },
    )
}
