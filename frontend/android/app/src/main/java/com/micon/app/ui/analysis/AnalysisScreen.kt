package com.micon.app.ui.analysis

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 분석 탭 — 프로토타입 `analysis` (이전 이름 리포트).
 * 내 목소리 카드(편한 음역 막대) · 나와 닮은 목소리 · 노래로 분석하기 · 음역 다시 측정하기.
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
    onDetail: () -> Unit,
    onLogin: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember { HoloResultStore(context) }
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val revision by UserStore.voiceDataRevision.collectAsStateWithLifecycle()
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    val ownerId = TokenManager.getUserId().takeIf { it > 0 && TokenManager.isLoggedIn() }
    val holo = remember(revision, ownerId) { store.read(ownerId) }
    val view = remember(holo, profile, notation) { holo?.let { VoiceView.from(it) } ?: profile?.let { VoiceView.from(it) } }

    MiconScreen(
        appBar = {
            MiconAppBar(actions = {
                // 비회원에게는 설정에 쓸 것이 없다 — 로그인만 보여 준다
                if (onLogin != null) MiconButton("로그인", onLogin, kind = MiconButtonKind.Text)
                else IconButton(onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "설정") }
            })
        },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (view == null) {
            MiconCard(kind = MiconCardKind.Elevated) {
                Text("아직 분석한 목소리가 없어요", style = MaterialTheme.typography.titleMedium)
                Text("30초만 부르면 편한 음역과 어울리는 곡을 찾아 드려요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                MiconButton("목소리 분석하기", onAnalyze, Modifier.fillMaxWidth())
                MiconButton("음역부터 단계별로 측정하기", onMeasureRange, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
            }
        } else {
            MyVoiceCard(view, onDetail)
            if (view.artists.isNotEmpty()) SimilarCard(view)
            MiconCard(kind = MiconCardKind.Tinted, modifier = Modifier.clickable(onClick = onSingAnalyze)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("노래로 분석하기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("한 곡 부르면 음역·음정 정확도를 같이 봐드려요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            MiconButton("음역 다시 측정하기", onMeasureRange, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
        }
    }
}

/** 화면이 보는 최소 모델. 홀로 결과와 서버 프로필 어느 쪽에서도 만든다 */
private data class VoiceView(
    val lowMidi: Int, val highMidi: Int, val comfortLow: Int, val comfortHigh: Int,
    val measuredAt: Long, val tags: List<Pair<String, MiconChipTone>>, val artists: List<Triple<String, String?, Int>>,
) {
    companion object {
        fun from(r: HoloResult) = VoiceView(r.lowMidi, r.highMidi, r.comfortLow, r.comfortHigh, r.measuredAt,
            tags = listOf(
                (if (r.octaves >= 2) "넓은 음역" else "또렷한") to MiconChipTone.Neutral,
                (if (r.phraseSec >= 5) "긴 호흡" else "부드러운") to MiconChipTone.Neutral,
                (if (r.stabilityMidis.zip(r.stability).filter { it.first <= 59 }.maxOfOrNull { it.second } ?: 0f >= 0.6f) "중저음 안정" else "음정 안정") to MiconChipTone.Success,
            ),
            artists = r.artists.map { Triple(it.name, it.imageUrl, (it.similarity * 100).roundToInt()) })
        fun from(p: UserStore.VoiceProfile) = VoiceView(p.lowMidi, p.highMidi, p.comfortLow, p.comfortHigh, p.measuredAt,
            tags = p.strengths.take(3).mapIndexed { i, s -> s to if (i == p.strengths.lastIndex) MiconChipTone.Success else MiconChipTone.Neutral },
            artists = emptyList())
    }
}

@Composable
private fun MyVoiceCard(v: VoiceView, onDetail: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MiconCard(kind = MiconCardKind.Elevated, modifier = Modifier.clickable(onClick = onDetail)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("내 목소리 · 편한 음역", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
                Text(NoteNames.displayRange(v.comfortLow, v.comfortHigh), fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, lineHeight = 32.sp)
            }
            Text(SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(v.measuredAt)), fontFamily = MiconMonoFamily, fontSize = 12.sp, color = muted)
        }
        Spacer(Modifier.height(10.dp))
        RangeBar(v)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { v.tags.forEach { (t, tone) -> MiconBadge(t, tone = tone) } }
    }
}

/**
 * 프로토타입 `.range` — 트랙 h12 위에 내 편한 음역(보라 그라디언트)이 자라나고, 아래 눈금 3개(낮은 도 · 중간 도 · 높은 도).
 * 자의 범위는 C2(36)~C6(84) 고정이라 모든 사용자 막대를 같은 자에 놓고 비교할 수 있다.
 */
@Composable
private fun RangeBar(v: VoiceView) = RangeBar(v.comfortLow, v.comfortHigh)

@Composable
internal fun RangeBar(low: Int, high: Int, song: IntRange? = null) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(low, high) { grow.snapTo(0f); grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ticks = listOf(48 to "낮은 도", 60 to "중간 도", 72 to "높은 도")
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
            ticks.forEach { (m, _) -> drawLine(Color(0xFFBDB8D0), Offset(size.width * f(m), trackTop + trackH), Offset(size.width * f(m), size.height), 1.dp.toPx()) }
        }
        // 눈금 글자는 3등분한 칸에 왼쪽·가운데·오른쪽으로 둔다. 비율 폭 상자에 넣으면
        // 칸이 글자보다 좁아 "낮은 도" 가 잘렸다.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            ticks.forEachIndexed { i, (_, label) ->
                Text(
                    label, Modifier.weight(1f), fontSize = 12.sp, color = muted,
                    textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                )
            }
        }
    }
}

@Composable
private fun SimilarCard(v: VoiceView) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나와 닮은 목소리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("${v.artists.size}명", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            v.artists.take(3).forEach { (name, image, pct) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    CompactDisc(title = name, imageUrl = image, size = 56.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text("$pct%", fontSize = 12.sp, fontFamily = MiconMonoFamily,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            repeat(3 - v.artists.take(3).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
