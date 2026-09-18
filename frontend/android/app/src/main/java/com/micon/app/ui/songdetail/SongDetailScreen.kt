package com.micon.app.ui.songdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.local.UserStore
import com.micon.app.ui.common.*
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.songdetail.SongDetailColors as Detail

@Composable
fun SongDetailScreen(songId: Long, onBack: () -> Unit, onPractice: (Int) -> Unit,
    onSettings: () -> Unit = {}, onLogin: (() -> Unit)? = null,
    carouselIds: List<Long> = emptyList(), artwork: Map<Long, String?> = emptyMap(), onSongSelected: (Long) -> Unit = {}) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SongDetailViewModel = viewModel(key = "song-$songId", factory = viewModelFactory {
        initializer { SongDetailViewModel(context.applicationContext, container.songs, container.mediaHttp, container.recommend, songId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val profileLoadFailed by UserStore.profileLoadFailed.collectAsStateWithLifecycle()
    PlaybackLifecycleEffect(vm, onResume = vm::enterScreen, onExit = vm::leaveScreen)
    SongDetailContent(state, profile, profileLoadFailed,
        onKey = vm::selectKey, onToggle = vm::toggle, onSeek = vm::seekTo, onListen = vm::listenAt,
        onPractice = { vm.leaveScreen(); onPractice(state.selectedKey) }, onRetryAssets = vm::retryAssets, onRetryFit = vm::refreshFit,
        onBack = { vm.leaveScreen(); onBack() }, onSettings = { vm.leaveScreen(); onSettings() },
        onLogin = onLogin?.let { login -> { vm.leaveScreen(); login() } }, onRetryDetail = vm::load,
        songId = songId, carouselIds = carouselIds, artwork = artwork,
        onSongSelected = { id -> if (id != songId) { vm.leaveScreen(); onSongSelected(id) } })
}

/** Pure content for previews and device UI tests; no API calls or account changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongDetailContent(state: SongDetailState, profile: UserStore.VoiceProfile?, profileLoadFailed: Boolean = false,
    onKey: (Int) -> Unit = {}, onToggle: () -> Unit = {}, onSeek: (Double) -> Unit = {}, onListen: (Double) -> Unit = {},
    onPractice: () -> Unit = {}, onRetryAssets: () -> Unit = {}, onRetryFit: () -> Unit = {},
    onBack: () -> Unit = {}, onSettings: () -> Unit = {}, onLogin: (() -> Unit)? = null, onRetryDetail: () -> Unit = {},
    songId: Long = state.detail?.summary?.id ?: 0L,
    carouselIds: List<Long> = emptyList(), artwork: Map<Long, String?> = emptyMap(), onSongSelected: (Long) -> Unit = {}) {
    val detail = state.detail
    val measured = profile?.let { MeasuredPitchRange(it.lowMidi, it.highMidi) }?.takeIf { it.valid }
    val pitch = remember(state.notes, state.sections, state.durationSec, measured, state.selectedKey, state.fit?.recommendedKey) {
        SongPitchPresentation.build(state.notes, state.durationSec, measured, state.selectedKey, state.fit?.recommendedKey, state.sections)
    }
    var selectedRegionId by rememberSaveable(songId, state.selectedKey) { mutableStateOf<String?>(null) }
    val selectedRegion = pitch.regions.firstOrNull { it.id == selectedRegionId }
    val scroll = rememberLazyListState()
    val collapsed by remember { derivedStateOf { scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 120 } }
    SongDetailTheme {
        Column(Modifier.fillMaxSize().background(Detail.background)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().background(if (collapsed) Detail.background else Color(0xFFF5F3FF))
                    .padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    val headerColor = if (collapsed) Detail.title else Ink
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = headerColor) }
                    Text(if (collapsed) detail?.summary?.title ?: "곡 상세" else "곡 상세", modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = headerColor, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    if (onLogin != null) TextButton(onClick = onLogin) { Text("로그인", color = headerColor) }
                    else IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "설정", tint = headerColor) }
                }
                LazyColumn(Modifier.weight(1f).testTag("song-detail-scroll"), state = scroll,
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    item("turntable") { SongDetailTurntable(state, songId, carouselIds, artwork, onSongSelected, onToggle) }
                    item("player") { SongDetailPlayer(state, onSeek) }
                    if (state.loading || detail == null) item("detail-status") {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.loading) CircularProgressIndicator(color = Detail.accent)
                            else {
                                Text(state.error ?: "곡을 불러오지 못했어요.", color = Detail.body)
                                TextButton(onClick = onRetryDetail) { Text("곡 정보 다시 불러오기") }
                            }
                        }
                    }
                    if (detail != null) {
                        item("range") { DetailSection { RangeMatchingCard(detail, measured, state.selectedKey, profileLoadFailed, pitch.notes) } }
                        item("settings") { DetailSection { SongSettingsCard(state, onKey, onRetryFit) } }
                        item("pitch") { DetailSection {
                            SongPitchCard(pitch, selectedRegion, state.positionSec, state.playable, state.assetsLoading,
                                onSelect = { selectedRegionId = it.id }, onListen = { selectedRegion?.let { onListen(it.start) } })
                        } }
                        if (state.assetsError != null || state.playbackError != null) item("assets-error") { DetailSection {
                            MiconCard(color = Detail.card) {
                                Text(state.playbackError ?: state.assetsError.orEmpty(), fontSize = 13.sp, color = Detail.body, lineHeight = 20.sp)
                                TextButton(onClick = onRetryAssets) { Text("반주·음표 다시 불러오기") }
                            }
                        } }
                        item("metadata") { DetailSection {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail.analysis?.keyName?.let { SoftChip("조성 $it", bg = Detail.card, fg = Detail.muted) }
                                detail.analysis?.tempoBpm?.let { SoftChip("$it BPM", bg = Detail.card, fg = Detail.muted) }
                                detail.analysis?.timeSignature?.let { SoftChip(it, bg = Detail.card, fg = Detail.muted) }
                            }
                        } }
                    }
                }

            }
            Surface(color = Detail.background, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    PrimaryButton(text = when { state.assetsLoading || state.keyLoading -> "반주 준비 중…"; !state.practiceEnabled -> "연습 데이터 준비 중"; state.selectedKey == 0 -> "원키로 연습 시작"; else -> "키 ${songKeyLabel(state.selectedKey)}로 연습 시작" },
                        onClick = onPractice, modifier = Modifier.fillMaxWidth().testTag("song-practice"), enabled = state.practiceEnabled)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) { content() }
}
