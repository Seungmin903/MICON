package com.micon.app.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.holo.HoloResult
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.molecules.OctaveRangeText
import com.micon.app.ui.design.molecules.karaokeNoteText
import com.micon.app.ui.design.molecules.karaokeRangeText
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import kotlin.math.roundToInt

private val Ser1 = Color(0xFF6C5CE7)
private val Ser2 = Color(0xFF0F9B6C)

/**
 * 분석 결과 — 프로토타입 `voiceResult`. 항목: 나의 음역대 · 음역대 지도 · 음역별 안정성 · 내 목소리에 맞는 음악 ·
 * 나와 닮은 목소리(서버 음색 분석이 끝나면) · 요약 태그. 카드는 60ms 간격으로 떠오른다.
 */
@Composable
fun VoiceResultScreen(
    result: HoloResult,
    saving: Boolean,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    saveError: String? = null,
    saved: Boolean = false,
    saveReady: Boolean = true,
    requiresLogin: Boolean = false,
    /** 분석 탭에서 다시 볼 때는 뒤로가기만 */
    onBack: (() -> Unit)? = null,
) {
    MiconScreen(
        background = Color.White,
        appBar = if (onBack != null) ({ com.micon.app.ui.design.organisms.MiconAppBar(title = "분석 결과", onBack = onBack) }) else null,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        bottom = if (onBack == null) ({
            val song = result.songId != null
            (saveError ?: if (!song) "추천은 노래를 부른 분석에서만 받을 수 있어요." else null)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = if (saveError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (song) MiconButton(when {
                requiresLogin -> "로그인하고 추천받기"
                saved -> "추천곡 보기"
                saving -> "추천 준비 중…"
                saveError != null -> "프로필 저장 다시 시도"
                else -> "분석 결과를 준비하고 있어요…"
            }, onSave, Modifier.fillMaxWidth(), enabled = !saving && (requiresLogin || saved || saveReady))
            else MiconButton("노래로 분석하고 추천받기", onRetry, Modifier.fillMaxWidth())
            MiconButton("다시 측정하기", onRetry, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline, enabled = !saving)
        }) else null,
    ) {
        if (onBack == null) Spacer(Modifier.height(12.dp))
        VoiceResultContent(result)
    }
}

/** 분석 탭과 측정 직후 화면이 공유하는 결과 본문. */
@Composable
fun VoiceResultContent(result: HoloResult, onArtistClick: (Long, String) -> Unit = { _, _ -> }) {
    FadeIn(0) { Text("편한 음역대를 찾았어요", style = MaterialTheme.typography.titleLarge) }
    FadeIn(1) { RangeMapCard(result) }
    FadeIn(2) { MatchingMusicCard(result) }
    if (result.artists.isNotEmpty()) FadeIn(4) { SimilarVoicesCard(result, onArtistClick) }
    else if (result.timbreSubmitted) FadeIn(4) {
        MiconCard(kind = MiconCardKind.Outlined) {
            Text("나와 닮은 목소리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("음색을 분석하는 중이에요. 잠시 뒤 여기서 확인할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FadeIn(order: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(360, delayMillis = 60 * order, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 14.dp.toPx() }) { content() }
}

@Composable
private fun Eyebrow(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)

/**
 * 음역대 지도. 카드 머리에 잰 음역을 크게 놓고, 그 아래 같은 자(전체 음역 = 0~100%) 위에
 * 전체·편안한 음역 막대를 겹친다. 숫자와 그림이 떨어져 있으면 같은 것을 두 번 읽게 된다.
 */
@Composable
private fun RangeMapCard(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("음역대 지도")
        Spacer(Modifier.height(6.dp))
        OctaveRangeText(r.lowMidi, r.highMidi, size = 26.sp, weight = FontWeight.Bold)
        val oct = r.octaves
        val octText = if (oct % 1.0 < 0.25) "${oct.toInt()}옥타브" else "${oct.toInt()}옥타브 반"
        // 범위는 위 제목이 노래방 표기로 보여 주므로 여기는 폭만. "음역 폭" 이라고 못 박아 옥타브 숫자와 헷갈리지 않게 한다.
        Text(
            "음역 폭 $octText",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        val span = (r.highMidi - r.lowMidi).coerceAtLeast(1).toFloat()
        fun f(m: Int) = ((m - r.lowMidi) / span).coerceIn(0f, 1f)
        // 눈금: 양 끝 + 중간 3개 (막대와 같은 자)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(80.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
                    Text(karaokeNoteText((r.lowMidi + p * span).roundToInt(), 12.sp, weight = FontWeight.SemiBold, short = true),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        listOf(
            Triple("전체 음역", Ser1, 0f to 1f),
            Triple("편안한 음역", Ser2, f(r.comfortLow) to f(r.comfortHigh)),
        ).forEachIndexed { i, (label, color, seg) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                GrowBar(color, seg.first, seg.second, delayMs = 100 * i)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GrowBar(color: Color, from: Float, to: Float, delayMs: Int) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, delayMillis = delayMs, easing = FastOutSlowInEasing)) }
    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFF0EDF9))) {
        Canvas(Modifier.matchParentSize()) {
            listOf(0.25f, 0.5f, 0.75f).forEach { x -> drawLine(Color(0xFFE9E5F6), Offset(size.width * x, 0f), Offset(size.width * x, size.height), 1.dp.toPx()) }
            val x0 = size.width * from
            val w = size.width * (to - from) * grow.value
            drawRoundRect(color, Offset(x0, size.height * 0.2f), Size(w, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
}

/**
 * 안정성 꺾은선 + 건반을 **한 가로축(MIDI)** 위에 위아래로 붙인 카드.
 * 예전에는 꺾은선이 샘플 순서, 건반이 흰건반 15개라 같은 음이 서로 다른 x 에 있었다.
 * 이제 [PitchAxis] 하나가 두 그림의 x 를 모두 정한다 — 점 바로 아래에 그 음의 건반이 온다.
 */
@Composable
private fun MatchingMusicCard(r: HoloResult) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val axis = remember(r.stabilityMidis, r.comfortLow, r.comfortHigh) { PitchAxis.of(r) }
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("내 목소리에 맞는 음악")
        Spacer(Modifier.height(10.dp))
        KeyValue("편한 음역", karaokeRangeText(r.comfortLow, r.comfortHigh, 14.sp, weight = FontWeight.Bold))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            // 좌측 눈금 라벨. 폭을 고정해야 아래 건반과 가로가 어긋나지 않는다
            Column(Modifier.width(34.dp).height(100.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("안정", "보통", "부담").forEach { Text(it, fontSize = 12.sp, color = muted) }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                StabilityLine(r, axis)
                Spacer(Modifier.height(6.dp))
                Keyboard(r, axis)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(karaokeNoteText(axis.low, 12.sp, weight = FontWeight.SemiBold, short = true), Modifier.weight(1f), color = muted)
                    Text(
                        karaokeRangeText(r.comfortLow, r.comfortHigh, 12.sp, weight = FontWeight.Bold, short = true),
                        Modifier.weight(2f), color = accent, textAlign = TextAlign.Center,
                    )
                    Text(
                        karaokeNoteText(axis.high, 12.sp, weight = FontWeight.SemiBold, short = true), Modifier.weight(1f),
                        color = muted, textAlign = TextAlign.End,
                    )
                }
            }
        }
        Text(
            "보라색 구간 안에서 멜로디가 도는 곡이 가장 편합니다. 위 곡선이 높을수록 그 음을 안정적으로 냈어요.",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 두 그림이 공유하는 가로축. 흰건반 목록을 좌표로 삼는다 —
 * 실제 피아노와 같은 간격이라 건반 위 어디에 음이 있는지 눈으로 읽힌다.
 */
private class PitchAxis(val low: Int, val high: Int, val whites: List<Int>) {
    /** 0..1 위치. 검은건반은 양옆 흰건반 사이에 온다 */
    fun fraction(midi: Int): Float {
        if (whites.isEmpty()) return 0.5f
        val m = midi.coerceIn(low, high)
        val below = whites.indexOfLast { it <= m }
        return when {
            below < 0 -> 0.5f / whites.size
            whites[below] == m -> (below + 0.5f) / whites.size
            else -> (below + 1f) / whites.size // 반음 위 — 흰건반 사이
        }
    }

    companion object {
        private val BLACK = setOf(1, 3, 6, 8, 10)
        private fun isWhite(midi: Int) = ((midi % 12) + 12) % 12 !in BLACK

        fun of(r: HoloResult): PitchAxis {
            val pool = r.stabilityMidis + listOf(r.comfortLow, r.comfortHigh)
            val low = (pool.min() - 2).let { m -> generateSequence(m) { it - 1 }.first(::isWhite) }
            val high = (pool.max() + 2).let { m -> generateSequence(m) { it + 1 }.first(::isWhite) }
            return PitchAxis(low, high, (low..high).filter(::isWhite))
        }
    }
}

/** 안정성 꺾은선. x 는 [axis], y 는 안정(위)~부담(아래) */
@Composable
private fun StabilityLine(r: HoloResult, axis: PitchAxis) {
    val best = r.stability.indices.maxByOrNull { r.stability[it] } ?: -1
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        fun x(midi: Int) = size.width * axis.fraction(midi)
        fun y(v: Float) = size.height * (0.08f + (1f - v) * 0.84f)
        listOf(0f, 0.5f, 1f).forEach { v ->
            drawLine(
                Color(0xFFE9E5F6), Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        if (r.stability.isEmpty()) return@Canvas
        val path = Path()
        r.stability.forEachIndexed { i, v ->
            val cx = x(r.stabilityMidis.getOrElse(i) { axis.low })
            val cy = y(v)
            if (i == 0) path.moveTo(cx, cy) else {
                val px = x(r.stabilityMidis.getOrElse(i - 1) { axis.low })
                val py = y(r.stability[i - 1])
                path.cubicTo(px + (cx - px) / 2, py, px + (cx - px) / 2, cy, cx, cy)
            }
        }
        drawPath(path, Ser1, style = Stroke(2.dp.toPx()))
        r.stability.forEachIndexed { i, v ->
            val cx = x(r.stabilityMidis.getOrElse(i) { axis.low })
            val radius = if (i == best) 6.dp.toPx() else 5.dp.toPx()
            drawCircle(Color.White, radius, Offset(cx, y(v)))
            drawCircle(Ser1, radius, Offset(cx, y(v)), style = Stroke(2.dp.toPx()))
        }
    }
}

/** 꺾은선과 같은 [axis] 위의 건반. 편안한 구간은 보라로 칠한다 */
@Composable
private fun Keyboard(r: HoloResult, axis: PitchAxis) {
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.fillMaxWidth().height(74.dp)) {
        val whites = axis.whites
        if (whites.isEmpty()) return@Canvas
        val ww = size.width / whites.size
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(Color(0xFFE7E3F4), Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        whites.forEachIndexed { i, m ->
            val on = m in r.comfortLow..r.comfortHigh
            drawRoundRect(
                if (on) Color(0xFFEDE9FF) else Color.White,
                Offset(i * ww + 1.dp.toPx(), 4.dp.toPx()),
                Size(ww - 2.dp.toPx(), size.height - 8.dp.toPx()),
                corner,
            )
        }
        whites.forEachIndexed { i, m ->
            val sharp = m + 1
            if (((sharp % 12) + 12) % 12 in setOf(1, 3, 6, 8, 10) && i < whites.size - 1) {
                val on = sharp in r.comfortLow..r.comfortHigh
                drawRoundRect(
                    if (on) accent else Color(0xFF3A3552),
                    Offset((i + 1) * ww - 4.25.dp.toPx(), 4.dp.toPx()),
                    Size(8.5.dp.toPx(), 44.dp.toPx()),
                    corner,
                )
            }
        }
        // 편안한 구간 틴트
        val li = whites.indexOfFirst { it >= r.comfortLow }.coerceAtLeast(0)
        val hi = whites.indexOfLast { it <= r.comfortHigh }.coerceAtLeast(li)
        drawRect(Ser1.copy(alpha = 0.13f), Offset(li * ww, 0f), Size((hi - li + 1) * ww, size.height))
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (v.isNotEmpty()) Text(v, fontFamily = MiconMonoFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** 값이 음이름(크기가 나뉜 AnnotatedString)일 때 */
@Composable
private fun KeyValue(k: String, v: androidx.compose.ui.text.AnnotatedString) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(v, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

/** 흰건반 15개 위에 편안한 구간을 보라색으로. 시작 건반은 편안한 음역 아래 5반음에서 */
@Composable
private fun Piano(r: HoloResult) {
    val first = (r.comfortLow - 5).let { m -> m - (if (listOf(1, 3, 6, 8, 10).contains(((m % 12) + 12) % 12)) 1 else 0) }
    val whites = generateSequence(first) { it + 1 }.filter { ((it % 12) + 12) % 12 !in setOf(1, 3, 6, 8, 10) }.take(15).toList()
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.fillMaxWidth().height(74.dp)) {
        val ww = size.width / 15f
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(Color(0xFFE7E3F4), Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        whites.forEachIndexed { i, m ->
            val on = m in r.comfortLow..r.comfortHigh
            drawRoundRect(if (on) Color(0xFFEDE9FF) else Color.White, Offset(i * ww + 1.dp.toPx(), 4.dp.toPx()), Size(ww - 2.dp.toPx(), size.height - 8.dp.toPx()), corner)
        }
        whites.forEachIndexed { i, m ->
            val sharp = m + 1
            if (((sharp % 12) + 12) % 12 in setOf(1, 3, 6, 8, 10) && i < 14) {
                val on = sharp in r.comfortLow..r.comfortHigh
                drawRoundRect(if (on) accent else Color(0xFF3A3552), Offset((i + 1) * ww - 4.25.dp.toPx(), 4.dp.toPx()), Size(8.5.dp.toPx(), 44.dp.toPx()), corner)
            }
        }
        // 편안한 구간 틴트
        val li = whites.indexOfFirst { it >= r.comfortLow }.coerceAtLeast(0)
        val hi = whites.indexOfLast { it <= r.comfortHigh }.coerceAtLeast(li)
        drawRect(Ser1.copy(alpha = 0.13f), Offset(li * ww, 0f), Size((hi - li + 1) * ww, size.height))
    }
    Row(Modifier.fillMaxWidth()) {
        val li = whites.indexOfFirst { it >= r.comfortLow }.coerceAtLeast(0)
        val hi = whites.indexOfLast { it <= r.comfortHigh }.coerceAtLeast(li)
        Spacer(Modifier.weight((li + 0.5f).coerceAtLeast(0.01f)))
        Text(karaokeNoteText(r.comfortLow, 12.sp, weight = FontWeight.Bold, short = true), color = accent)
        Spacer(Modifier.weight((hi - li).coerceAtLeast(1).toFloat()))
        Text(karaokeNoteText(r.comfortHigh, 12.sp, weight = FontWeight.Bold, short = true), color = accent)
        Spacer(Modifier.weight((14.5f - hi).coerceAtLeast(0.01f)))
    }
}

@Composable
private fun SimilarVoicesCard(r: HoloResult, onArtistClick: (Long, String) -> Unit) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("나와 닮은 목소리")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            r.artists.take(3).forEach { a ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clickable { onArtistClick(a.artistId, a.name) }) {
                        CompactDisc(title = a.name, imageUrl = a.imageUrl, size = 56.dp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.name, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
                    Text("${(a.similarity * 100).roundToInt()}%", fontFamily = MiconMonoFamily, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            repeat(3 - r.artists.take(3).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
