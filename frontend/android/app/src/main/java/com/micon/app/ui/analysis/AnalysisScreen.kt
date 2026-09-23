package com.micon.app.ui.analysis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.holo.HoloResult
import com.micon.app.data.holo.HoloResultStore
import com.micon.app.data.local.UserStore
import com.micon.app.ui.holo.VoiceResultContent
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.molecules.OctaveRangeText
import com.micon.app.ui.design.molecules.karaokeNoteText
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.design.tokens.MiconMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 분석 탭 — 프로토타입 `analysis` (이전 이름 리포트).
 * 내 목소리 카드(편한 음역 막대) · 나와 닮은 목소리 · 음색 다시 측정하기 · 음역 다시 측정하기.
 * 데이터는 홀로 결과(기기 저장) → 없으면 서버 프로필(UserStore) → 없으면 빈 상태.
 */
@Composable
fun AnalysisScreen(
    /** 처음부터 — 음역 측정 → 음색 측정 */
    onAnalyze: () -> Unit,
    /** 음색만 다시 — 노래를 불러 음색을 본다. 음역은 지난 값을 쓴다 */
    onSingAnalyze: () -> Unit = onAnalyze,
    /** 음역만 다시 */
    onMeasureRange: () -> Unit = onAnalyze,
    onSettings: () -> Unit,
    latestResult: HoloResult? = null,
    onArtistClick: (Long, String) -> Unit = { _, _ -> },
    onRecommend: () -> Unit = {},
    recommendLabel: String = "추천곡 보기",
    recommendEnabled: Boolean = false,
    recommendError: String? = null,
    onLogin: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { HoloResultStore(context) }
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val revision by UserStore.voiceDataRevision.collectAsStateWithLifecycle()
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    val ownerId = TokenManager.getUserId().takeIf { it > 0 && TokenManager.isLoggedIn() }
    val holo = remember(revision, ownerId) { store.read(ownerId) }
    val result = latestResult ?: holo
    val view = remember(profile, notation) { profile?.let { VoiceView.from(it) } }

    MiconScreen(
        appBar = {
            MiconHeader(onSettings = onSettings, onLogin = onLogin)
        },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (result == null && view == null) {
            MiconCard(kind = MiconCardKind.Elevated) {
                Text("아직 분석한 목소리가 없어요", style = MaterialTheme.typography.titleMedium)
                Text("30초만 부르면 편한 음역과 어울리는 곡을 찾아 드려요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                MiconButton("목소리 분석하기", onAnalyze, Modifier.fillMaxWidth())
            }
        } else {
            if (result != null) VoiceResultContent(result, onArtistClick)
            else if (view != null) {
                MyVoiceCard(view)
                ProfileStabilityCard(profile!!)
            }
            if (result?.songId != null) {
                recommendError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                MiconButton(recommendLabel, onRecommend, Modifier.fillMaxWidth(), enabled = recommendEnabled)
            }
            MiconButton("음색 다시 측정하기", onSingAnalyze, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
            MiconButton("음역 다시 측정하기", onMeasureRange, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
        }
    }
}

/** 상세 결과 파일 없이 서버 프로필만 복원됐을 때 화면이 보는 값. */
private data class VoiceView(
    val lowMidi: Int, val highMidi: Int, val comfortLow: Int, val comfortHigh: Int,
    val measuredAt: Long,
) {
    companion object {
        fun from(p: UserStore.VoiceProfile) = VoiceView(p.lowMidi, p.highMidi, p.comfortLow, p.comfortHigh, p.measuredAt,
        )
    }
}

@Composable
private fun MyVoiceCard(v: VoiceView) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MiconCard(kind = MiconCardKind.Elevated) {
        Text("음역대 지도", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("전체 음역", style = MaterialTheme.typography.bodySmall, color = muted)
                OctaveRangeText(v.lowMidi, v.highMidi, size = 24.sp)
                Spacer(Modifier.height(8.dp))
                Text("편한 음역", style = MaterialTheme.typography.bodySmall, color = muted)
                OctaveRangeText(v.comfortLow, v.comfortHigh, size = 24.sp)
            }
            Text(SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(v.measuredAt)), fontFamily = MiconMonoFamily, fontSize = 12.sp, color = muted)
        }
        Spacer(Modifier.height(10.dp))
        RangeBar(v)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProfileStabilityCard(profile: UserStore.VoiceProfile) {
    if (profile.stability.isEmpty()) return
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("음역별 안정성", style = MaterialTheme.typography.titleMedium)
        profile.stability.zip(profile.stabilityLabels).forEach { (value, note) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 프로토타입 `.range` — 트랙 h12 위에 내 편한 음역(보라 그라디언트)이 자라나고, 아래 눈금 3개(1옥타브 도 · 2옥타브 도 · 3옥타브 도).
 * 자의 범위는 C2(36)~C6(84) 고정이라 모든 사용자 막대를 같은 자에 놓고 비교할 수 있다.
 */
@Composable
private fun RangeBar(v: VoiceView) = RangeBar(v.comfortLow, v.comfortHigh)

@Composable
internal fun RangeBar(low: Int, high: Int, song: IntRange? = null) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(low, high) { grow.snapTo(0f); grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ticks = listOf(48, 60, 72)
    fun f(m: Int) = ((m - 36) / 48f).coerceIn(0f, 1f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val trackTop = 5.dp.toPx(); val trackH = 12.dp.toPx()
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFEDEAF7), Color(0xFFE3DEF6))), Offset(0f, trackTop), Size(size.width, trackH), CornerRadius(6.dp.toPx()))
            val x0 = size.width * f(low); val w = size.width * (f(high) - f(low)) * grow.value
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6C5CE7), Color(0xFF8E7BFF)), startX = x0, endX = x0 + w), Offset(x0, trackTop), Size(w, trackH), CornerRadius(6.dp.toPx()))
            if (song != null) {
                val sx = size.width * f(song.first); val sw = size.width * (f(song.last) - f(song.first))
                drawRoundRect(Color(0xFFC92A5E).copy(alpha = 0.07f), Offset(sx, 0f), Size(sw, size.height), CornerRadius(11.dp.toPx()))
                drawRoundRect(Color(0xFFC92A5E), Offset(sx, 0f), Size(sw, size.height), CornerRadius(11.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
            }
            ticks.forEach { m -> drawLine(Color(0xFFBDB8D0), Offset(size.width * f(m), trackTop + trackH), Offset(size.width * f(m), size.height), 1.dp.toPx()) }
        }
        // 눈금 글자는 3등분한 칸에 왼쪽·가운데·오른쪽으로 둔다. 비율 폭 상자에 넣으면
        // 칸이 글자보다 좁아 잘렸다.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            ticks.forEachIndexed { i, m ->
                Text(
                    karaokeNoteText(m, 12.sp, weight = FontWeight.SemiBold), Modifier.weight(1f), color = muted,
                    textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                )
            }
        }
    }
}
