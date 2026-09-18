package com.micon.app.ui.songdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.R
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.recommend.TurntableSheetShape
import com.micon.app.ui.theme.RecommendBackground
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

/** Retains the LP, tonearm, neighbouring records and curved black sheet of the original screen. */
@Composable
internal fun SongDetailTurntable(state: SongDetailState, songId: String, songIds: List<String>,
    artwork: Map<String, String?>, onSongSelected: (String) -> Unit, onToggle: () -> Unit) {
    val ids = songIds.takeIf { songId in it } ?: listOf(songId)
    val pager = rememberPagerState(initialPage = ids.indexOf(songId).coerceAtLeast(0)) { ids.size }
    val select by rememberUpdatedState(onSongSelected)
    val currentSong by rememberUpdatedState(songId)
    LaunchedEffect(ids) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            ids.getOrNull(index)?.takeIf { it != currentSong }?.let(select)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("song-turntable")) {
        val w = maxWidth
        val heroHeight = w * 1.04f
        val diameter = w * .706f
        Box(Modifier.fillMaxWidth().height(heroHeight).background(RecommendBackground)) {
            HorizontalPager(state = pager, pageSize = PageSize.Fixed(w * .71f),
                contentPadding = PaddingValues(horizontal = w * .145f),
                modifier = Modifier.fillMaxWidth().padding(top = 30.dp).testTag("song-carousel")) { page ->
                val id = ids[page]
                val away = ((page - pager.currentPage) - pager.currentPageOffsetFraction).absoluteValue.coerceAtMost(1.4f)
                Box(Modifier.fillMaxWidth().height(diameter + w * .165f), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.offset(y = w * .165f * away), contentAlignment = Alignment.Center) {
                        VinylArt(if (id == songId) state.detail?.summary?.imageUrl ?: artwork[id] else artwork[id], diameter,
                            fallbackText = "")
                        if (id == songId) FilledIconButton(onClick = onToggle, enabled = state.playable,
                            modifier = Modifier.size(56.dp).testTag("song-preview"),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SongDetailColors.background.copy(alpha = .8f), contentColor = Color.White,
                                disabledContainerColor = SongDetailColors.background.copy(alpha = .65f), disabledContentColor = SongDetailColors.muted)) {
                            Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (state.playing) "반주 일시 정지" else "반주 미리 듣기", Modifier.size(30.dp))
                        }
                    }
                }
            }
            Box(Modifier.offset(x = w * .692f, y = 0.dp).size(w * .196f)
                .clip(CircleShape).background(Color.White.copy(alpha = .18f)))
            Image(painterResource(R.drawable.tonearm), null,
                Modifier.offset(x = w * (.79f - .62f * .282f / 2), y = 16.dp)
                    .size(width = w * .62f * .282f, height = w * .62f)
                    .graphicsLayer { rotationZ = 62f; transformOrigin = TransformOrigin(.5f, .06f) })
            // The very same arc shape as the original black turntable sheet.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(w * .29f)
                .background(SongDetailColors.background, TurntableSheetShape))
            if (ids.size > 1) Text("${pager.currentPage + 1} / ${ids.size} · LP를 넘겨 다른 곡 보기",
                color = SongDetailColors.muted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }
    }
}

@Composable
internal fun SongDetailPlayer(state: SongDetailState, onSeek: (Double) -> Unit) {
    val song = state.detail?.summary ?: return
    var dragging by remember(song.id, state.selectedKey) { mutableStateOf<Float?>(null) }
    val duration = state.durationSec.coerceAtLeast(1.0)
    val progress = dragging ?: (state.positionSec / duration).toFloat().coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(song.title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
            color = SongDetailColors.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(song.artist, color = SongDetailColors.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Slider(value = progress, enabled = state.playable, onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it * duration) }; dragging = null },
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(thumbColor = SongDetailColors.accent, activeTrackColor = SongDetailColors.accent,
                inactiveTrackColor = SongDetailColors.track, disabledInactiveTrackColor = SongDetailColors.track))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(songTime(progress * duration), fontSize = 11.sp, color = SongDetailColors.muted)
            Text(songTime(state.durationSec), fontSize = 11.sp, color = SongDetailColors.muted)
        }
        Spacer(Modifier.height(10.dp))
        Text(when { state.assetsLoading || state.keyLoading -> "반주를 준비하고 있어요…"
            !state.playable -> "반주가 준비되면 미리 들을 수 있어요"
            else -> "${songKeyLabel(state.selectedKey)} · 반주 미리 듣기" },
            fontSize = 11.sp, color = SongDetailColors.muted, textAlign = TextAlign.Center)
    }
}
