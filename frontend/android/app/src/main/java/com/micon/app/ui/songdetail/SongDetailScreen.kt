package com.micon.app.ui.songdetail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.ScoringConstants
import com.micon.app.ui.analysis.RangeBar
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.launch

/**
 * 곡 상세 — 프로토타입 `songDetail`. "이 곡을 내가 부를 수 있나"에 한 화면으로 답한다.
 * 곡 정보 → 음역 매치(내 음역·곡 음역을 같은 자에) → 키 조절(막대·문장이 같이 바뀜) → 어려운 구간 → 연습하기.
 * 예전 곡 매치 상세·곡 상세 두 화면을 하나로 합쳤고, 어둡던 톤을 라이트로 통일했다.
 */
@Composable
fun SongDetailScreen(songId: String, onBack: () -> Unit, onPractice: (Int) -> Unit,
    onSettings: () -> Unit = {}, onLogin: (() -> Unit)? = null,
    carouselIds: List<String> = emptyList(), artwork: Map<String, String?> = emptyMap(), onSongSelected: (String) -> Unit = {}) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SongDetailViewModel = viewModel(key = "song-$songId", factory = viewModelFactory {
        initializer { SongDetailViewModel(context.applicationContext, container.songs, container.mediaHttp, container.recommend, songId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val liked by container.favorites.likedIds.collectAsStateWithLifecycle()
    val loggedIn = TokenManager.isLoggedIn()
    val scope = rememberCoroutineScope()
    LaunchedEffect(loggedIn) { if (loggedIn) runCatching { container.favorites.refresh() } }
    fun toggleLike() {
        if (!loggedIn) { onLogin?.invoke() ?: Toast.makeText(context, "로그인하면 찜할 수 있어요.", Toast.LENGTH_SHORT).show(); return }
        scope.launch { runCatching { container.favorites.toggle(songId) }.onFailure { Toast.makeText(context, describeBackendError(it), Toast.LENGTH_SHORT).show() } }
    }
    SongDetailContent(state, profile, liked = songId in liked, onLike = ::toggleLike,
        onKey = vm::selectKey, onPractice = { vm.leaveScreen(); onPractice(state.selectedKey) },
        onBack = { vm.leaveScreen(); onBack() }, onRetryDetail = vm::load)
}

/** 순수 콘텐츠 — 프리뷰·UI 테스트용 */
@Composable
fun SongDetailContent(
    state: SongDetailState, profile: UserStore.VoiceProfile?, liked: Boolean = false, onLike: () -> Unit = {},
    onKey: (Int) -> Unit = {}, onPractice: () -> Unit = {}, onBack: () -> Unit = {}, onRetryDetail: () -> Unit = {},
) {
    val detail = state.detail
    val key = state.selectedKey
    MiconScreen(
        appBar = {
            MiconAppBar(onBack = onBack, actions = {
                IconButton(onLike, Modifier.size(MiconSpace.touchTarget)) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = if (liked) "찜 해제" else "찜",
                        tint = if (liked) Color(0xFFC92A5E) else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(23.dp))
                }
            })
        },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.md),
        bottom = {
            MiconButton(
                text = when { state.assetsLoading || state.keyLoading -> "반주 준비 중…"; !state.practiceEnabled -> "연습 데이터 준비 중"; else -> "이 노래 연습하기" },
                onClick = onPractice, modifier = Modifier.fillMaxWidth().testTag("song-practice"), enabled = state.practiceEnabled,
            )
        },
    ) {
        if (detail == null) {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "곡을 불러오지 못했어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MiconButton("다시 불러오기", onRetryDetail, kind = com.micon.app.ui.design.atoms.MiconButtonKind.Text)
                }
            }
            return@MiconScreen
        }
        val fit = state.fit
        val song = songRange(detail, fit)
        // 서버가 사용한 안정 음역을 우선 표시하고, 서버 값이 없는 로컬 계산만 UserStore 로 보완한다.
        val mine = fit?.rangeFit?.let { range ->
            val low = range.userStableLowMidi
            val high = range.userStableHighMidi
            if (low != null && high != null && high >= low) low to high else null
        } ?: profile?.let { it.comfortLow to it.comfortHigh }
        Header(detail)
        RangeCard(mine, song, key, state.fitLoading, profile == null)
        KeyCard(key, onKey)
        DifficultCard(state, fit)
    }
}

private fun songRange(detail: SongDetail, fit: SongFit?): IntRange? {
    val low = fit?.rangeFit?.songCoreLowMidi ?: detail.analysis?.coreLowMidi ?: detail.analysis?.lowestMidi
    val high = fit?.rangeFit?.songCoreHighMidi ?: detail.analysis?.coreHighMidi ?: detail.analysis?.highestMidi
    return if (low != null && high != null && high >= low) low..high else null
}

@Composable
private fun Header(detail: SongDetail) {
    val s = detail.summary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CompactDisc(title = s.title, artist = s.artist, imageUrl = s.imageUrl, size = 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(s.artist, s.releaseDate?.take(4), detail.genres.firstOrNull() ?: s.genre).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RangeCard(mine: Pair<Int, Int>?, song: IntRange?, key: Int, loading: Boolean, noProfile: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MiconCard(kind = MiconCardKind.Elevated) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("내 음역과 곡 음역", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            if (loading && !noProfile) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        val shifted = song?.let { (it.first + key)..(it.last + key) }
        RangeBar(low = mine?.first ?: 0, high = mine?.second ?: 0, song = shifted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 6.dp).background(Color(0xFF6C5CE7), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp)); Text("내 음역", fontSize = 12.sp, color = muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 10.dp).border(1.5.dp, Color(0xFFC92A5E), RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(6.dp)); Text("곡 음역", fontSize = 12.sp, color = muted)
            }
        }
        when {
            noProfile -> Text("목소리를 분석하면 내 음역을 함께 볼 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = muted)
            song == null -> Text("곡 음역 정보가 아직 없어요.", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }
}

@Composable
private fun KeyCard(key: Int, onKey: (Int) -> Unit) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("키 조절", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KeyStep("−", enabled = key > ScoringConstants.KEY_SHIFT_MIN) { onKey(key - 1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (key > 0) "+$key" else "$key", fontFamily = MiconMonoFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text(when { key == 0 -> "원래 키"; key > 0 -> "+${key}키"; else -> "${key}키" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            KeyStep("+", enabled = key < ScoringConstants.KEY_SHIFT_MAX) { onKey(key + 1) }
        }
    }
}

@Composable
internal fun KeyStep(symbol: String, size: androidx.compose.ui.unit.Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(size), enabled = enabled) {
        Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

/** 곡 구간 중 난이도가 표시된 것 2개. 없으면 카드 생략 */
@Composable
private fun DifficultCard(state: SongDetailState, fit: SongFit?) {
    val hard = state.sections.filter { it.difficultyType != "NONE" && it.difficultyType.isNotBlank() }
        .sortedByDescending { it.difficultyScore ?: 0.0 }.take(2)
    val strain = fit?.strainSections?.original.orEmpty()
    if (hard.isEmpty() && strain.isEmpty()) return
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("어려운 구간", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        if (hard.isNotEmpty()) hard.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.sectionName.ifBlank { s.sectionType }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (label, tone) = when (s.difficultyType) {
                    "HIGH_NOTE" -> "높은 음" to MiconChipTone.Warning
                    "LONG_BREATH" -> "긴 호흡" to MiconChipTone.Neutral
                    "FAST_LYRICS" -> "빠른 가사" to MiconChipTone.Neutral
                    "PITCH_JUMP" -> "음 도약" to MiconChipTone.Warning
                    else -> "복합" to MiconChipTone.Challenge
                }
                MiconBadge(s.description?.takeIf { it.length <= 10 } ?: label, tone = tone)
            }
        } else strain.take(2).forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(s.startSec / 60).toInt()}:${"%02d".format((s.startSec % 60).toInt())} 부근", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                MiconBadge("${s.maxExcessSemitones}음 높음", tone = MiconChipTone.Warning)
            }
        }
    }
}
