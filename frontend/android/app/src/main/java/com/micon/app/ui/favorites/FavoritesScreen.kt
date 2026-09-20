package com.micon.app.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.micon.app.data.favorites.SetlistRepository
import com.micon.app.ui.common.RefreshOnResume
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.MiconActionChip
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.atoms.MiconField
import com.micon.app.ui.design.molecules.SongRow
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 찜 탭 — 프로토타입 `favorites`: 오늘의 세트리스트 카드 + 찜한 곡(매치 %) + 세트리스트를 한곳에 */
class FavoritesViewModel(
    private val favorites: FavoriteRepository,
    private val setlists: SetlistRepository,
    private val recommend: RecommendRepository,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val songs: List<SongSummary> = emptyList(),
        val setlists: List<Setlist> = emptyList(),
        /** songId → 매치 % */
        val match: Map<String, Int?> = emptyMap(),
        val busy: Boolean = false,
        val toast: String? = null,
    ) {
        /** 매치 80% 이상인 찜 곡 — "오늘의 세트리스트" 재료 */
        val todays: List<SongSummary> get() = songs.filter { (match[it.id] ?: 0) >= 80 }.take(5)
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val songs = favorites.refresh()
                val lists = runCatching { setlists.list() }.getOrDefault(emptyList())
                _state.update { it.copy(loading = false, songs = songs, setlists = lists) }
                val missing = songs.filter { it.id !in _state.value.match }
                val match = missing.associate { s -> s.id to runCatching { recommend.fit(s.id)?.overallFit?.let { f -> (f * 100).roundToInt() } }.getOrNull() }
                _state.update { it.copy(match = it.match + match) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = describeBackendError(e, "찜 목록을 불러오지 못했어요.")) } }
        }
    }

    fun unlike(songId: String) {
        viewModelScope.launch {
            try { if (!favorites.toggle(songId)) _state.update { it.copy(songs = it.songs.filterNot { s -> s.id == songId }) } }
            catch (e: Exception) { _state.update { it.copy(toast = describeBackendError(e, "찜을 바꾸지 못했어요.")) } }
        }
    }

    fun createSetlist(name: String) {
        if (name.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { setlists.create(name.trim()); _state.update { it.copy(setlists = setlists.list(), busy = false, toast = "세트리스트를 만들었어요.") } }
            catch (e: Exception) { _state.update { it.copy(busy = false, toast = describeBackendError(e, "세트리스트를 만들지 못했어요.")) } }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}

@Composable
fun FavoritesScreen(onSongClick: (String) -> Unit, onSetlistClick: (Long) -> Unit, onSettings: () -> Unit, onPractice: (String) -> Unit = onSongClick) {
    val container = rememberAppContainer()
    val vm: FavoritesViewModel = viewModel(factory = viewModelFactory { initializer { FavoritesViewModel(container.favorites, container.setlists, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    RefreshOnResume { vm.load() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    var creating by remember { mutableStateOf(false) }

    MiconScreen(
        appBar = { MiconAppBar(title = "찜", actions = { IconButton(onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "설정") } }) },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
    ) {
        val todays = state.todays
        if (todays.isNotEmpty()) MiconCard(kind = MiconCardKind.Tinted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("오늘의 세트리스트", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                    val minutes = (todays.sumOf { it.durationMs ?: 210_000L } / 60_000.0).roundToInt()
                    Text("내 음역에 맞는 ${todays.size}곡 · 약 ${minutes}분", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                MiconButton("시작", { onPractice(todays.first().id) }, Modifier.height(44.dp))
            }
        }
        when {
            state.error != null -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!, style = MaterialTheme.typography.bodyMedium, color = muted)
                MiconButton("다시 불러오기", vm::load, kind = MiconButtonKind.Text)
            }
            state.loading && state.songs.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.songs.isEmpty() -> Text("아직 찜한 곡이 없어요. 곡 상세에서 ♡ 를 눌러 모아 두세요.", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 16.dp))
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.songs.forEach { song ->
                    val pct = state.match[song.id]
                    SongRow(title = song.title, artist = song.artist, imageUrl = song.imageUrl, onClick = { onSongClick(song.id) },
                        trailing = { if (pct != null) MiconBadge("$pct%", tone = if (pct >= 88) MiconChipTone.Success else MiconChipTone.Neutral) })
                }
            }
        }
        // 세트리스트
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("세트리스트", style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            MiconActionChip("새로 만들기", onClick = { creating = true })
        }
        if (state.setlists.isEmpty()) Text("세트리스트를 만들어 찜한 곡을 묶어 두세요.", style = MaterialTheme.typography.bodyMedium, color = muted)
        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.setlists.forEach { s ->
                SongRow(title = s.name, artist = "${s.songCount}곡", onClick = { onSetlistClick(s.id) })
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("새 세트리스트") },
            text = { MiconField(name, { name = it }, "이름 (1~100자)") },
            confirmButton = { TextButton({ vm.createSetlist(name); creating = false }, enabled = name.isNotBlank() && !state.busy) { Text("만들기") } },
            dismissButton = { TextButton({ creating = false }) { Text("취소") } },
        )
    }
    state.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(1800); vm.consumeToast() }
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            MiconCard(kind = MiconCardKind.Elevated, contentPadding = 12.dp) { Text(msg, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
