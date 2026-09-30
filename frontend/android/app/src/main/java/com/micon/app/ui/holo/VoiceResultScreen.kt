package com.micon.app.ui.holo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.holo.HoloResult
import com.micon.app.range.VoiceQualityResult
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.molecules.OctaveRangeText
import com.micon.app.ui.design.molecules.karaokeNoteText
import com.micon.app.ui.design.molecules.karaokeRangeText
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import kotlin.math.roundToInt

private val Ser1 = Color(0xFF6C5CE7)
private val Ser3 = Color(0xFFC2410C)

// 음역 지도와 발성 유지 점수가 함께 쓰는 규칙. 편한 음역은 어디서나 보라,
// 전체 음역은 연보라, 발성 점수 곡선과 편한 구간은 보라색으로 표시한다.
private val RangeAll = Color(0xFFC9C1F5)
private val RangeComfort = Ser1
private val Ink = Color(0xFF3A3552)
/** 두 그림 왼쪽 라벨 칸. 같은 폭이라 그림의 x 시작점이 같다 */
private val Gutter = 48.dp

/**
 * 분석 결과 — 프로토타입 `voiceResult`. 항목: 나의 음역대 · 음역대 지도 · 음역별 안정성 · 내 목소리에 맞는 음악 ·
 * 나와 닮은 목소리(서버 음색 분석이 끝나면). 카드는 60ms 간격으로 떠오른다.
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

/** 분석 완료 화면과 분석 탭이 같은 결과 UI를 공유한다. */
@Composable
fun VoiceResultContent(result: HoloResult, showStatus: Boolean = true) {
    if (showStatus) FadeIn(0) { MiconBadge("측정 완료", tone = MiconChipTone.Success) }
    // 한 카드 안의 두 그림이 같은 자를 쓴다 — 같은 음은 위아래에서 같은 x 에 온다
    val axis = remember(result) { PitchAxis.of(result) }
    FadeIn(1) { RangeMapCard(result, axis) }
    if (result.artists.isNotEmpty()) FadeIn(2) { SimilarVoicesCard(result) }
    else if (result.timbreSubmitted) FadeIn(2) {
        MiconCard(kind = MiconCardKind.Outlined) {
            Text("나와 닮은 목소리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("음색을 분석하는 중이에요. 잠시 뒤 분석 탭에서 확인할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * 음역대 지도. 카드 머리에 편한 음역을 크게(화면 제목·분석 탭 카드와 같은 값), 전체 음역은 보조 줄로.
 * 그 아래 [PitchAxis] 자 위에 전체·편한 음역 막대와 발성 유지 점수를 함께 놓는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeMapCard(r: HoloResult, axis: PitchAxis) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("음역대 지도", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (r.hasComfortRange) OctaveRangeText(r.comfortLow, r.comfortHigh, size = 26.sp, weight = FontWeight.Bold, spacedOctave = true)
        else Text("80점 이상 구간 없음", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        val oct = r.octaves
        val octText = if (oct % 1.0 < 0.25) "${oct.toInt()} 옥타브" else "${oct.toInt()} 옥타브 반"
        // "음역 폭" 이라고 못 박아 옥타브 숫자와 헷갈리지 않게 한다.
        // 두 조각을 따로 두어, 큰 글꼴에서 줄이 넘치면 음이름 가운데가 아니라 조각 사이에서 넘긴다.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                buildAnnotatedString {
                    append("전체 ")
                    append(karaokeRangeText(r.lowMidi, r.highMidi, 14.sp, weight = FontWeight.SemiBold, spacedOctave = true))
                },
                style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, softWrap = false,
            )
            Text("· 음역 폭 $octText", style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(14.dp))
        AxisRow(axis)
        Spacer(Modifier.height(6.dp))
        (listOf(Triple("전체", RangeAll, axis.start(r.lowMidi) to axis.end(r.highMidi))) +
            if (r.hasComfortRange) listOf(Triple("편한", RangeComfort, axis.start(r.comfortLow) to axis.end(r.comfortHigh)))
            else emptyList()).forEachIndexed { i, (label, color, seg) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(Gutter), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                }
                GrowBar(color, seg.first, seg.second, delayMs = 100 * i, guides = axis.guides)
            }
        }
        // Older saved results can lack this optional score. Keep their measured range visible
        // without showing an alarming "could not calculate" message after completion.
        if (r.voiceQuality?.points?.isNotEmpty() == true) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.height(18.dp))
            StabilitySection(r, axis)
        } else if (r.hasComfortRange) {
            Spacer(Modifier.height(8.dp))
            Text("편한 구간은 음역 측정값을 바탕으로 추정했어요.", fontSize = 12.sp, color = muted)
        }
    }
}

/** 두 그림 위에 똑같이 두는 눈금 행. 왼쪽 [Gutter] 만큼 비워 그림과 x 를 맞춘다 */
@Composable
private fun AxisRow(axis: PitchAxis) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(Gutter))
        AxisTicks(axis, Modifier.weight(1f))
    }
}

/**
 * 축 양끝 + 범위 안의 "도" 를 그 음의 x([PitchAxis.fraction]) 에 놓는다. 끝 라벨은 칸 안으로 당기고,
 * 겹치는 라벨은 뺀다(양끝 우선) — 글꼴을 키워도 글자가 포개지지 않는다.
 */
@Composable
private fun AxisTicks(axis: PitchAxis, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ticks = axis.ticks
    Layout(
        content = {
            ticks.forEach { m ->
                Text(karaokeNoteText(m, 12.sp, weight = FontWeight.SemiBold, short = true), color = muted, maxLines = 1, softWrap = false)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val xs = ticks.mapIndexed { i, m ->
            (axis.fraction(m) * width - placeables[i].width / 2f).roundToInt()
                .coerceIn(0, (width - placeables[i].width).coerceAtLeast(0))
        }
        val taken = mutableListOf<IntRange>()
        val shown = ticks.indices.map { i ->
            val span = (xs[i] - gap)..(xs[i] + placeables[i].width + gap)
            (taken.none { it.first <= span.last && span.first <= it.last }).also { if (it) taken += span }
        }
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p -> if (shown[i]) p.place(xs[i], 0) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GrowBar(color: Color, from: Float, to: Float, delayMs: Int, guides: List<Float>) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, delayMillis = delayMs, easing = FastOutSlowInEasing)) }
    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFF0EDF9))) {
        Canvas(Modifier.matchParentSize()) {
            guides.forEach { x -> drawLine(Color(0xFFE9E5F6), Offset(size.width * x, 0f), Offset(size.width * x, size.height), 1.dp.toPx()) }
            val x0 = size.width * from
            val w = size.width * (to - from) * grow.value
            drawRoundRect(color, Offset(x0, size.height * 0.2f), Size(w, size.height * 0.6f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
}

/**
 * 발성 유지 점수 곡선 + 건반을 **한 가로축(MIDI)** 위에 위아래로 붙인 섹션.
 * 예전에는 꺾은선이 샘플 순서, 건반이 흰건반 15개라 같은 음이 서로 다른 x 에 있었다.
 * 이제 [PitchAxis] 하나가 두 그림의 x 를 모두 정한다 — 점 바로 아래에 그 음의 건반이 온다.
 */
@Composable
private fun StabilitySection(r: HoloResult, axis: PitchAxis) {
    if (r.voiceQuality == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("음역별 발성 유지 점수", fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    AxisRow(axis)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        // 좌측 눈금 라벨. [Gutter] 폭이라 위 막대·아래 건반과 가로가 어긋나지 않는다
        Column(Modifier.width(Gutter).height(100.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("100", "50", "0").forEach { Text(it, fontSize = 12.sp, color = muted) }
        }
        Column(Modifier.weight(1f)) {
            StabilityLine(r, axis)
            Spacer(Modifier.height(6.dp))
            Keyboard(r, axis)
        }
    }
}

/**
 * 음역 지도와 발성 유지 점수가 공유하는 가로축. 흰건반 목록을 좌표로 삼는다 —
 * 실제 피아노와 같은 간격이라 건반 위 어디에 음이 있는지 눈으로 읽힌다.
 * 범위는 전체 음역·편한 음역·안정성 측정음을 모두 담고 양옆에 흰건반 하나씩 여유를 둔다.
 */
private class PitchAxis(val low: Int, val high: Int, val whites: List<Int>) {
    /** 0..1 위치(건반 가운데). 검은건반은 양옆 흰건반 사이에 온다 */
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

    /** 그 음 건반의 왼쪽 끝. 막대·건반 틴트가 같은 경계를 쓴다 */
    fun start(midi: Int): Float = edge(midi, left = true)

    /** 그 음 건반의 오른쪽 끝 */
    fun end(midi: Int): Float = edge(midi, left = false)

    private fun edge(midi: Int, left: Boolean): Float {
        if (whites.isEmpty()) return 0.5f
        val n = whites.size.toFloat()
        val m = midi.coerceIn(low, high)
        val below = whites.indexOfLast { it <= m }.coerceAtLeast(0)
        return if (whites[below] == m) (below + if (left) 0f else 1f) / n
        else (below + 1f + if (left) -0.25f else 0.25f) / n // 검은건반은 흰건반 경계에 반 칸 폭
    }

    /** 눈금: 양끝 먼저, 그다음 범위 안의 "도" — 겹치면 앞의 것이 남는다 */
    val ticks: List<Int> = listOf(low, high) + whites.filter { it % 12 == 0 && it != low && it != high }

    /** 세로 보조선 자리("도" 건반 가운데) */
    val guides: List<Float> = whites.filter { it % 12 == 0 }.map(::fraction)

    companion object {
        private val BLACK = setOf(1, 3, 6, 8, 10)
        private fun isWhite(midi: Int) = ((midi % 12) + 12) % 12 !in BLACK

        fun of(r: HoloResult): PitchAxis {
            val pool = r.stabilityMidis + listOf(r.lowMidi, r.highMidi, r.comfortLow, r.comfortHigh)
            val low = (pool.min() - 1).let { m -> generateSequence(m) { it - 1 }.first(::isWhite) }
            val high = (pool.max() + 1).let { m -> generateSequence(m) { it + 1 }.first(::isWhite) }
            return PitchAxis(low, high, (low..high).filter(::isWhite))
        }
    }
}

/**
 * 0.3초를 유지하지 못해 기록된 0점은 중간에서만 숨긴다. 양끝의 짧은 시도와
 * 충분히 유지했지만 발성 품질 때문에 받은 실제 0점은 그대로 보여 준다.
 * 원본 점수는 저장·전송에 그대로 남고, 숨긴 음을 건너뛰는 선은 점선으로 그린다.
 */
internal fun visibleStabilityPoints(
    midis: List<Int>, scores: List<Float>, quality: VoiceQualityResult?,
): List<Pair<Int, Float>> {
    val values = midis.zip(scores)
    if (quality == null || quality.points.isEmpty()) return values
    val firstMidi = quality.points.minOf { it.midi }
    val lastMidi = quality.points.maxOf { it.midi }
    val measured = quality.points.associateBy { it.midi }
    return values.filter { (midi, _) ->
        val point = measured[midi]
        point == null || midi == firstMidi || midi == lastMidi || point.score != 0.0 || point.holdScore > 0.0
    }
}

/** 측정 점 사이를 완만한 곡선으로 잇는다. 미측정 반음은 점선을 써서 보간임을 드러낸다. */
@Composable
private fun StabilityLine(r: HoloResult, axis: PitchAxis) {
    val points = remember(r.stabilityMidis, r.stability, r.voiceQuality) {
        visibleStabilityPoints(r.stabilityMidis, r.stability, r.voiceQuality)
    }
    val bestMidi = points.maxByOrNull { it.second }?.first
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        fun x(midi: Int) = size.width * axis.fraction(midi)
        fun y(v: Float) = size.height * (0.08f + (1f - v) * 0.84f)
        if (r.hasComfortRange) {
            val c0 = size.width * axis.start(r.comfortLow)
            drawRect(RangeComfort.copy(alpha = 0.13f), Offset(c0, 0f), Size(size.width * axis.end(r.comfortHigh) - c0, size.height))
        }
        axis.guides.forEach { gx -> drawLine(Color(0xFFE9E5F6), Offset(size.width * gx, 0f), Offset(size.width * gx, size.height), 1.dp.toPx()) }
        listOf(0f, 0.5f, 1f).forEach { v ->
            drawLine(
                Color(0xFFE9E5F6), Offset(0f, y(v)), Offset(size.width, y(v)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        if (points.isEmpty()) return@Canvas
        if (r.voiceQuality != null) {
            points.zipWithNext().forEach { (previous, current) ->
                val px = x(previous.first)
                val cx = x(current.first)
                val py = y(previous.second)
                val cy = y(current.second)
                val middle = (px + cx) / 2f
                val path = Path().apply {
                    moveTo(px, py)
                    cubicTo(middle, py, middle, cy, cx, cy)
                }
                val gap = current.first - previous.first > 1
                drawPath(path, RangeComfort, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (gap) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
            }
        } else {
            val path = Path()
            points.forEachIndexed { i, (midi, value) ->
                val cx = x(midi)
                val cy = y(value)
                if (i == 0) path.moveTo(cx, cy) else {
                    val px = x(points[i - 1].first)
                    val py = y(points[i - 1].second)
                    path.cubicTo(px + (cx - px) / 2, py, px + (cx - px) / 2, cy, cx, cy)
                }
            }
            drawPath(path, Ink, style = Stroke(2.dp.toPx()))
        }
        points.forEach { (midi, v) ->
            val cx = x(midi)
            val radius = if (midi == bestMidi) 6.dp.toPx() else 5.dp.toPx()
            val color = if (r.voiceQuality != null && v == 0f) Color(0xFFD64A55)
                else if (r.voiceQuality != null) RangeComfort else Ink
            drawCircle(Color.White, radius, Offset(cx, y(v)))
            drawCircle(color, radius, Offset(cx, y(v)), style = Stroke(2.dp.toPx()))
        }
    }
}

/** 꺾은선과 같은 [axis] 위의 건반. 편한 구간은 보라로 칠한다 */
@Composable
private fun Keyboard(r: HoloResult, axis: PitchAxis) {
    Canvas(Modifier.fillMaxWidth().height(74.dp)) {
        val whites = axis.whites
        if (whites.isEmpty()) return@Canvas
        val ww = size.width / whites.size
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(Color(0xFFE7E3F4), Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        whites.forEachIndexed { i, m ->
            val on = r.hasComfortRange && m in r.comfortLow..r.comfortHigh
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
                val on = r.hasComfortRange && sharp in r.comfortLow..r.comfortHigh
                drawRoundRect(
                    if (on) RangeComfort else Ink,
                    Offset((i + 1) * ww - 4.25.dp.toPx(), 4.dp.toPx()),
                    Size(8.5.dp.toPx(), 44.dp.toPx()),
                    corner,
                )
            }
        }
        // 편한 구간 틴트 — 위 곡선 틴트·음역대 지도 막대와 같은 경계
        if (r.hasComfortRange) {
            val c0 = size.width * axis.start(r.comfortLow)
            drawRect(RangeComfort.copy(alpha = 0.13f), Offset(c0, 0f), Size(size.width * axis.end(r.comfortHigh) - c0, size.height))
        }
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
private fun SimilarVoicesCard(r: HoloResult) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Eyebrow("나와 닮은 목소리")
        Spacer(Modifier.height(6.dp))
        r.artists.take(3).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactDisc(title = a.name, imageUrl = a.imageUrl, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${(a.similarity * 100).roundToInt()}%", fontFamily = MiconMonoFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
