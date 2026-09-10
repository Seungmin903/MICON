package com.micon.app.ui.favorites

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.SongRow
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 곡 카탈로그를 한 번 읽어 id → SongSummary 조회에 쓴다. */
class FavoritesViewModel(private val repo: SongRepository, private val recommend: RecommendRepository) : ViewModel() {
    data class State(val loading: Boolean = true, val songs: Map<String, SongSummary> = emptyMap(), val keys: Map<String, Int?> = emptyMap())
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val songs = runCatching { repo.search("", analyzedOnly = true, limit = 300) }.getOrDefault(emptyList())
            UserStore.seedLikesIfEmpty(songs.map { it.id })
            _state.value = State(false, songs.associateBy { it.id })
            val keys = songs.associate { s -> s.id to runCatching { recommend.fit(s.id)?.recommendedKey }.getOrNull() }
            _state.value = _state.value.copy(keys = keys)
        }
    }
}

private enum class Tab(val label: String) { ALL("전체"), PRACTICING("연습 중"), SETLIST("내 세트리스트") }

/** Figma "노래 찜 목록" / "노래 찜 목록 - 세트리스트" */
@Composable
fun FavoritesScreen(onSongClick: (String) -> Unit, onSetlistClick: (String) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: FavoritesViewModel = viewModel(factory = viewModelFactory { initializer { FavoritesViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val liked by UserStore.liked.collectAsStateWithLifecycle()
    val setlists by UserStore.setlists.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.ALL) }
    var query by remember { mutableStateOf("") }

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
            Text(if (tab == Tab.SETLIST) "최근 생성순" else "최근 연습순", fontSize = 15.sp, color = Grey, modifier = Modifier.weight(1f))
            Text("편집", fontSize = 15.sp, color = Grey, modifier = Modifier.clickable { /* TODO: 편집 모드 */ })
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = GreyLight)

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            return
        }
        if (tab == Tab.SETLIST) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { UserStore.addSetlist("새 리스트 ${setlists.size + 1}") }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(76.dp).background(Color(0xFF15161C), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(50.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, "새 리스트", Modifier.size(28.dp), tint = Ink)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("새로운 리스트", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                }
                items(setlists, key = { it.id }) { sl ->
                    val first = sl.songIds.firstOrNull()?.let { state.songs[it] }
                    SongRow(title = sl.name, artist = first?.artist ?: "${sl.songIds.size}곡", imageUrl = first?.imageUrl, onClick = { onSetlistClick(sl.id) })
                }
            }
        } else {
            val songs = liked.mapNotNull { state.songs[it] }.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                        keyBadge = state.keys[song.id], liked = true, onLike = { UserStore.toggleLike(song.id) },
                    )
                }
                if (songs.isEmpty()) item { Text("찜한 곡이 없어요. 연습 탭에서 하트를 눌러 보세요.", Modifier.padding(24.dp), color = Grey) }
            }
        }
    }
}

/** Figma "노래 찜 목록 - 세트리스트 세부" */
@Composable
fun SetlistScreen(setlistId: String, onBack: () -> Unit, onSongClick: (String) -> Unit) {
    val container = rememberAppContainer()
    val vm: FavoritesViewModel = viewModel(factory = viewModelFactory { initializer { FavoritesViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val setlists by UserStore.setlists.collectAsStateWithLifecycle()
    val liked by UserStore.liked.collectAsStateWithLifecycle()
    val sl = setlists.firstOrNull { it.id == setlistId }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("나의 세트리스트", onBack) {
            IconButton(onClick = { /* TODO: 이름 변경 · 삭제 메뉴 */ }) { Icon(Icons.Filled.MoreHoriz, "더보기", tint = Ink) }
        }
        if (sl == null) { Text("세트리스트를 찾을 수 없습니다.", Modifier.padding(24.dp), color = Grey); return }
        val songs = sl.songIds.mapNotNull { state.songs[it] }
        Surface(color = Color.White, shadowElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                VinylArt(songs.firstOrNull()?.imageUrl, 120.dp, fallbackText = sl.name)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(sl.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("총 ${songs.size}곡", fontSize = 15.sp, color = Grey)
                }
                IconButton(onClick = { /* TODO: 곡 편집 */ }) { Icon(Icons.Outlined.Edit, "편집", tint = Ink) }
            }
        }
        if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
        else LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(songs, key = { it.id }) { song ->
                SongRow(
                    title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                    keyBadge = state.keys[song.id], liked = song.id in liked, onLike = { UserStore.toggleLike(song.id) },
                )
            }
        }
    }
}
