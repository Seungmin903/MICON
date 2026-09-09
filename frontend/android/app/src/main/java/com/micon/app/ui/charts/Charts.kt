package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import com.micon.app.ui.theme.Red
import com.micon.app.ui.theme.Teal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 음역대 지도
// ---------------------------------------------------------------------------

data class RangeRow(val label: String, val lowMidi: Int, val highMidi: Int, val color: Color, val fadeTail: Boolean = false)

/**
 * Figma "음역대 지도": 위에 C3 G3 C4 G4 C5 축, 행마다 라벨 + 회색 기준선 + 색 막대.
 * 여러 구간(불안정 음역 2개)은 같은 label 로 행을 두 번 주면 한 줄에 그린다.
 */
@Composable
fun RangeMapChart(
    rows: List<RangeRow>,
    modifier: Modifier = Modifier,
    axisLow: Int = 48,
    axisHigh: Int = 72,
    ticks: List<Int> = listOf(48, 55, 60, 67, 72),
    labelWidth: Dp = 84.dp,
    labelOf: (Int) -> String = { ScoringMath.midiName(it) },
) {
    val measurer = rememberTextMeasurer()
    val grouped = rows.groupBy { it.label }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Spacer(Modifier.width(labelWidth))
            Canvas(Modifier.weight(1f).fillMaxSize()) {
                val span = (axisHigh - axisLow).toFloat()
                ticks.forEach { m ->
                    val x = size.width * (m - axisLow) / span
                    val layout = measurer.measure(labelOf(m), TextStyle(fontSize = 11.sp, color = Grey))
                    drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), 0f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        grouped.forEach { (label, segs) ->
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(labelWidth - 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.width(10.dp))
                Canvas(Modifier.weight(1f).fillMaxSize()) {
                    val span = (axisHigh - axisLow).toFloat()
                    val y = size.height / 2f
                    drawLine(GreyLight, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                    val barH = 8.dp.toPx()
                    segs.forEach { s ->
                        val x0 = size.width * (s.lowMidi - axisLow).coerceAtLeast(0) / span
                        val x1 = size.width * (s.highMidi - axisLow).coerceAtMost(axisHigh - axisLow) / span
                        drawRoundRect(s.color, Offset(x0, y - barH / 2), Size(max(x1 - x0, barH), barH), CornerRadius(barH / 2))
                        if (s.fadeTail) {
                            val x2 = min(size.width, x1 + size.width * 2 / span)
                            drawRect(Brush.horizontalGradient(listOf(s.color, s.color.copy(alpha = 0f)), x1, x2), Offset(x1, y - barH / 2), Size(x2 - x1, barH))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 음역별 안정성 꺾은선
// ---------------------------------------------------------------------------

/** values: 0(부담)~1(안정). xLabels 는 values 와 같은 개수. */
@Composable
fun StabilityLineChart(values: List<Float>, xLabels: List<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Row(modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.width(36.dp).height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("안정", "보통", "부담").forEach { Text(it, fontSize = 12.sp, color = Grey) }
        }
        Canvas(Modifier.weight(1f).fillMaxSize()) {
            val chartH = 120.dp.toPx()
            val pad = 8.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            val n = values.size
            fun x(i: Int) = pad + (size.width - 2 * pad) * i / max(1, n - 1)
            fun y(v: Float) = pad + (chartH - 2 * pad) * (1f - v.coerceIn(0f, 1f))
            listOf(1f, 0.5f, 0f).forEach { v -> drawLine(GreyLight, Offset(0f, y(v)), Offset(size.width, y(v)), 1.5f, pathEffect = dash) }
            for (i in 0 until n) drawLine(GreyLight, Offset(x(i), y(1f)), Offset(x(i), y(0f)), 1.5f, pathEffect = dash)
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, PurpleMid, style = Stroke(2.5f))
            values.forEachIndexed { i, v -> drawCircle(Purple, radius = 4f, center = Offset(x(i), y(v))) }
            xLabels.forEachIndexed { i, label ->
                val layout = measurer.measure(label, TextStyle(fontSize = 11.sp, color = Grey))
                drawText(layout, topLeft = Offset(x(i) - layout.size.width / 2f, chartH + 6.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 피아노 음역
// ---------------------------------------------------------------------------

private fun isBlackKey(midi: Int) = when (midi % 12) { 1, 3, 6, 8, 10 -> true; else -> false }

/** C3~C5 건반. [hlLow, hlHigh] 구간은 연보라(흰 건반)/보라(검은 건반)로 강조. */
@Composable
fun PianoRangeChart(hlLow: Int, hlHigh: Int, modifier: Modifier = Modifier, lowMidi: Int = 48, highMidi: Int = 72, caption: String? = null) {
    val measurer = rememberTextMeasurer()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val whites = (lowMidi..highMidi).filter { !isBlackKey(it) }
            val labelH = 16.dp.toPx()
            val keyTop = labelH + 4.dp.toPx()
            val keyH = size.height - keyTop
            val w = size.width / whites.size
            val xOf = HashMap<Int, Float>()
            whites.forEachIndexed { i, m ->
                val x = i * w
                xOf[m] = x
                val inRange = m in hlLow..hlHigh
                drawRect(if (inRange) Lavender else Color.White, Offset(x, keyTop), Size(w, keyH))
                drawRect(Color(0xFF9A9AA6), Offset(x, keyTop), Size(w, keyH), style = Stroke(1.5f))
            }
            (lowMidi..highMidi).filter { isBlackKey(it) }.forEach { m ->
                val left = xOf[m - 1] ?: return@forEach
                val bw = w * 0.6f
                val inRange = m in hlLow..hlHigh
                drawRect(if (inRange) Color(0xFF7B6CF0) else Color(0xFF1B1B25), Offset(left + w - bw / 2, keyTop), Size(bw, keyH * 0.62f))
            }
            listOf(lowMidi, 55, 60, 67, highMidi).distinct().forEach { m ->
                val x = xOf[m] ?: return@forEach
                val hl = m == hlLow || m == hlHigh
                val layout = measurer.measure(ScoringMath.midiName(m), TextStyle(fontSize = 10.sp, color = if (hl) Ink else Grey, fontWeight = if (hl) FontWeight.Bold else FontWeight.Normal))
                drawText(layout, topLeft = Offset(x + w / 2 - layout.size.width / 2f, 0f))
                if (hl) drawCircle(Purple, 3.dp.toPx(), Offset(x + w / 2, labelH + 1.dp.toPx()))
            }
        }
        caption?.let {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val whites = (lowMidi..highMidi).filter { !isBlackKey(it) }
                val w = size.width / whites.size
                val i0 = whites.indexOfFirst { it >= hlLow }.coerceAtLeast(0)
                val i1 = whites.indexOfLast { it <= hlHigh }.coerceAtLeast(i0)
                val x0 = i0 * w; val x1 = (i1 + 1) * w
                drawLine(Purple, Offset(x0, 0f), Offset(x0, size.height), 2f)
                drawLine(Purple, Offset(x1, 0f), Offset(x1, size.height), 2f)
                drawLine(Purple, Offset(x0, size.height), Offset(x1, size.height), 2f)
            }
            Text(it, fontSize = 11.sp, color = Purple, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 간편 분석용 건반: 기준음(targetMidi) 건반을 강조하고, 감지된 음(detectedMidi, 소수 허용)을 건반 위 마커로
 * 연속적으로 보여 준다(반음 사이는 건반 중심 사이를 보간 → 살짝 높거나 낮은 것도 바로 보임).
 * 건반을 누르면 [onKeyTap] 으로 그 음을 돌려준다(기준음 변경·미리 듣기).
 * 표시 범위는 기준음 아래 8반음 ~ 위 8반음을 흰 건반 경계로 맞춘 약 2옥타브.
 */
@Composable
fun PianoTargetKeyboard(
    targetMidi: Int,
    detectedMidi: Double?,
    matched: Boolean,
    modifier: Modifier = Modifier,
    onKeyTap: ((Int) -> Unit)? = null,
) {
    var lowMidi = targetMidi - 8
    while (isBlackKey(lowMidi)) lowMidi--
    var highMidi = lowMidi + 16
    while (isBlackKey(highMidi)) highMidi++
    val whites = (lowMidi..highMidi).filter { !isBlackKey(it) }
    val measurer = rememberTextMeasurer()
    val detectedColor = if (matched) Teal else Red
    // 콜백은 매 프레임 바뀔 수 있으므로(50 fps 상태 갱신) pointerInput 키에 넣지 않는다 — 넣으면 탭이 끝나기 전에 제스처가 재시작된다.
    val tapHandler by rememberUpdatedState(onKeyTap)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(lowMidi, highMidi) {
                detectTapGestures { pos ->
                    val onKeyTap = tapHandler ?: return@detectTapGestures
                    val labelH = 18.dp.toPx()
                    val keyTop = labelH + 4.dp.toPx()
                    val keyH = size.height - keyTop - 22.dp.toPx()
                    val w = size.width / whites.size
                    if (pos.y < keyTop || pos.y > keyTop + keyH) return@detectTapGestures
                    val whiteIndex = (pos.x / w).toInt().coerceIn(0, whites.size - 1)
                    val white = whites[whiteIndex]
                    // 검은 건반 영역(위쪽 62 %, 흰 건반 경계 ± 0.3w)
                    if (pos.y < keyTop + keyH * 0.62f) {
                        val bw = w * 0.6f
                        for (m in listOf(white + 1, white - 1)) {
                            if (m < lowMidi || m > highMidi || !isBlackKey(m)) continue
                            val leftWhite = whites.indexOf(m - 1)
                            if (leftWhite < 0) continue
                            val left = leftWhite * w + w - bw / 2
                            if (pos.x in left..(left + bw)) { onKeyTap(m); return@detectTapGestures }
                        }
                    }
                    onKeyTap(white)
                }
            },
    ) {
        val labelH = 18.dp.toPx()
        val keyTop = labelH + 4.dp.toPx()
        val markerH = 22.dp.toPx()
        val keyH = size.height - keyTop - markerH
        val w = size.width / whites.size
        val bw = w * 0.6f
        val xOf = HashMap<Int, Float>()          // 건반 중심 x (흰·검은 모두)
        whites.forEachIndexed { i, m ->
            val x = i * w
            xOf[m] = x + w / 2
            val isTarget = m == targetMidi
            drawRoundRect(if (isTarget) Lavender else Color.White, Offset(x, keyTop), Size(w, keyH), CornerRadius(3.dp.toPx()))
            drawRoundRect(if (isTarget) Purple else Color(0xFF9A9AA6), Offset(x, keyTop), Size(w, keyH), CornerRadius(3.dp.toPx()), style = Stroke(if (isTarget) 3.dp.toPx() else 1.5f))
        }
        (lowMidi..highMidi).filter { isBlackKey(it) }.forEach { m ->
            val leftWhite = whites.indexOf(m - 1)
            if (leftWhite < 0) return@forEach
            val left = leftWhite * w + w - bw / 2
            xOf[m] = left + bw / 2
            val isTarget = m == targetMidi
            drawRoundRect(if (isTarget) PurpleMid else Color(0xFF1B1B25), Offset(left, keyTop), Size(bw, keyH * 0.62f), CornerRadius(2.dp.toPx()))
            if (isTarget) drawRoundRect(Purple, Offset(left, keyTop), Size(bw, keyH * 0.62f), CornerRadius(2.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
        // 라벨: C 음과 기준음
        (lowMidi..highMidi).filter { it == targetMidi || (it % 12 == 0 && abs(it - targetMidi) > 1) }.forEach { m ->   // 기준음 옆 C 라벨은 겹치므로 생략
            val cx = xOf[m] ?: return@forEach
            val isTarget = m == targetMidi
            val layout = measurer.measure(ScoringMath.midiName(m), TextStyle(fontSize = if (isTarget) 12.sp else 10.sp, color = if (isTarget) Purple else Grey, fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Normal))
            drawText(layout, topLeft = Offset((cx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), 0f))
        }
        // 감지된 음 마커: 건반 중심 사이를 보간해 연속적으로 이동
        detectedMidi?.let { d ->
            val clamped = d.coerceIn(lowMidi.toDouble(), highMidi.toDouble())
            val lo = kotlin.math.floor(clamped).toInt()
            val hi = kotlin.math.ceil(clamped).toInt()
            val xLo = xOf[lo] ?: return@let
            val xHi = xOf[hi] ?: xLo
            val x = xLo + (xHi - xLo) * (clamped - lo).toFloat()
            val bottom = keyTop + keyH
            drawLine(detectedColor.copy(alpha = 0.85f), Offset(x, keyTop - 2.dp.toPx()), Offset(x, bottom), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(detectedColor, 7.dp.toPx(), Offset(x, bottom + markerH / 2))
            drawCircle(Color.White, 3.dp.toPx(), Offset(x, bottom + markerH / 2))
            if (d < lowMidi || d > highMidi) {   // 범위 밖: 화살표 대신 테두리 삼각형 힌트
                val dir = if (d < lowMidi) -1f else 1f
                val ax = if (dir < 0) 6.dp.toPx() else size.width - 6.dp.toPx()
                val path = Path().apply {
                    moveTo(ax, bottom + markerH / 2); lineTo(ax - dir * 10.dp.toPx(), bottom + markerH / 2 - 6.dp.toPx()); lineTo(ax - dir * 10.dp.toPx(), bottom + markerH / 2 + 6.dp.toPx()); close()
                }
                drawPath(path, detectedColor)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// BPM 게이지 · 프레이즈 · 링 · 막대 · 육각형
// ---------------------------------------------------------------------------

@Composable
fun BpmGaugeRow(bpmText: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(46.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(r, r)
            drawCircle(Purple, r - 3.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawArc(Purple, 140f, 260f, false, Offset(c.x - r * 0.62f, c.y - r * 0.62f), Size(r * 1.24f, r * 1.24f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawLine(Purple, c, Offset(c.x, c.y - r * 0.55f), 4f, StrokeCap.Round)
            drawCircle(Purple, 4f, c)
        }
        Spacer(Modifier.width(12.dp))
        Text(bpmText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.width(6.dp))
        Text("BPM", fontSize = 17.sp, color = Ink)
    }
}

@Composable
fun PhraseSegments(filled: Int, total: Int = 7, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until total) {
            Box(Modifier.weight(1f).fillMaxSize().background(if (i < filled) Purple else Lavender, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
fun RingProgress(percent: Int, modifier: Modifier = Modifier, size: Dp = 104.dp, stroke: Dp = 10.dp, caption: String = "종합 적합도") {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val rect = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Lavender, 0f, 360f, false, rect, sz, style = Stroke(sw))
            drawArc(Purple, -90f, 360f * percent / 100f, false, rect, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$percent", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Text(caption, fontSize = 10.sp, color = Grey)
        }
    }
}

@Composable
fun MetricBar(label: String, value: Float, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(36.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(Lavender, cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(Purple, size = Size(size.width * value.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp)) }
    }
}

/** 육각형 레이더. values 6개(0~1), labels 6개(위에서 시계 방향). */
@Composable
fun RadarHexagon(values: List<Float>, labels: List<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(170.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.68f
        fun pt(i: Int, k: Float): Offset {
            val a = -PI / 2 + i * PI / 3
            return Offset(c.x + (r * k * cos(a)).toFloat(), c.y + (r * k * sin(a)).toFloat())
        }
        listOf(1f, 0.66f, 0.33f).forEach { k ->
            val p = Path(); for (i in 0 until 6) { val o = pt(i, k); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }; p.close()
            drawPath(p, GreyLight, style = Stroke(1.5f))
        }
        for (i in 0 until 6) drawLine(GreyLight, c, pt(i, 1f), 1.5f)
        val poly = Path()
        values.take(6).forEachIndexed { i, v -> val o = pt(i, v.coerceIn(0.05f, 1f)); if (i == 0) poly.moveTo(o.x, o.y) else poly.lineTo(o.x, o.y) }
        poly.close()
        drawPath(poly, Brush.verticalGradient(listOf(PurpleMid.copy(alpha = 0.75f), Purple.copy(alpha = 0.35f))))
        drawPath(poly, Purple, style = Stroke(2f))
        labels.take(6).forEachIndexed { i, label ->
            val o = pt(i, 1.28f)
            val layout = measurer.measure(label, TextStyle(fontSize = 11.sp, color = Ink))
            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
        }
    }
}

// ---------------------------------------------------------------------------
// 키 변화 그래프 (노래 상세)
// ---------------------------------------------------------------------------

/**
 * Figma "키 변화 그래프". melody: (초, MIDI) 곡 멜로디(보라 실선), shifted: 키 조절 후 멜로디(분홍 점선).
 * band: 사용자 음역(연보라 띠). y 라벨은 C 음(C3·C4·C5).
 */
@Composable
fun KeyChangeGraph(
    melody: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    shifted: List<Pair<Double, Double>>? = null,
    bandLow: Int? = null,
    bandHigh: Int? = null,
    /** 세로축 라벨 (기본 C3/C4…; 노래방식이면 KaraokeNames.koreanShort) */
    labelOf: (Int) -> String = { ScoringMath.midiName(it) },
    /** 가장 높은 지점 옆에 쓸 말 (null 이면 없음) */
    peakLabel: String? = null,
    height: Dp = 320.dp,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (melody.isEmpty()) return@Canvas
        val all = melody.map { it.second } + (shifted?.map { it.second } ?: emptyList()) + listOfNotNull(bandLow?.toDouble(), bandHigh?.toDouble())
        val lo = (all.min() - 2).toFloat(); val hi = (all.max() + 2).toFloat()
        val t0 = melody.first().first; val t1 = melody.last().first
        val left = 58.dp.toPx()
        fun x(t: Double) = left + ((t - t0) / (t1 - t0).coerceAtLeast(1.0) * (size.width - left)).toFloat()
        fun y(m: Double) = ((hi - m) / (hi - lo) * size.height).toFloat()
        // 축
        drawLine(Color(0xFFB0B0B8), Offset(left, 0f), Offset(left, size.height), 3f)
        // 사용자 음역 띠
        if (bandLow != null && bandHigh != null) {
            drawRect(Lavender, Offset(left, y(bandHigh.toDouble())), Size(size.width - left, y(bandLow.toDouble()) - y(bandHigh.toDouble())))
            drawLine(Purple, Offset(left, y(bandHigh.toDouble())), Offset(size.width, y(bandHigh.toDouble())), 2f)
            drawLine(Purple, Offset(left, y(bandLow.toDouble())), Offset(size.width, y(bandLow.toDouble())), 2f)
        }
        // 도·솔 라벨 (5도 간격이면 어느 음역이든 2~4개는 보인다) + 옅은 가로선
        for (m in kotlin.math.ceil(lo).toInt()..kotlin.math.floor(hi).toInt()) {
            if (m % 12 != 0 && m % 12 != 7) continue
            drawLine(Color(0xFFE6E6EB), Offset(left, y(m.toDouble())), Offset(size.width, y(m.toDouble())), 1.5f)
            val layout = measurer.measure(labelOf(m), TextStyle(fontSize = 12.sp, color = Grey))
            drawText(layout, topLeft = Offset(4f, y(m.toDouble()) - layout.size.height / 2f))
        }
        fun smooth(points: List<Pair<Double, Double>>): Path {
            val p = Path()
            points.forEachIndexed { i, (t, mm) ->
                val px = x(t); val py = y(mm)
                if (i == 0) p.moveTo(px, py) else {
                    val (pt, pm) = points[i - 1]
                    val cx = (x(pt) + px) / 2
                    p.cubicTo(cx, y(pm), cx, py, px, py)
                }
            }
            return p
        }
        shifted?.let {
            drawPath(smooth(it), Pink, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
            val top = it.maxByOrNull { p -> p.second }!!
            drawCircle(Pink, 9f, Offset(x(top.first), y(top.second))); drawCircle(Color.White, 4f, Offset(x(top.first), y(top.second)))
        }
        drawPath(smooth(melody), Purple, style = Stroke(3f))
        val top = melody.maxByOrNull { it.second }!!
        drawCircle(Purple, 9f, Offset(x(top.first), y(top.second))); drawCircle(Color.White, 4f, Offset(x(top.first), y(top.second)))
        if (peakLabel != null) {
            val layout = measurer.measure(peakLabel, TextStyle(fontSize = 12.sp, color = Purple, fontWeight = FontWeight.SemiBold))
            val px = (x(top.first) - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width)
            val py = (y(top.second) - layout.size.height - 12f).coerceAtLeast(0f)
            drawText(layout, topLeft = Offset(px, py))
        }
    }
}

/** 키 변화 그래프 범례: 보라 실선 · 분홍 점선 · 검정 */
@Composable
fun KeyChangeLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp).height(12.dp).background(PurpleMid, RoundedCornerShape(6.dp)))
        Canvas(Modifier.width(60.dp).height(12.dp)) {
            drawLine(Pink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        Box(Modifier.width(44.dp).height(12.dp).background(Color.Black, RoundedCornerShape(6.dp)))
    }
}

// ---------------------------------------------------------------------------
// 음성 간편 분석 게이지
// ---------------------------------------------------------------------------

/**
 * Figma 간편 분석 2/2: 기준음 원(호 진행) + 반원 게이지 + 바늘.
 * centsOffset 이 null 이면 바늘을 숨긴다(무성). matched 이면 청록, 아니면 빨강.
 */
@Composable
fun PitchGauge(
    targetName: String,
    neighborNames: List<String>,
    centsOffset: Float?,
    matched: Boolean,
    holdProgress: Float,
    modifier: Modifier = Modifier,
    gaugeHeight: Dp = 190.dp,
    ringSize: Dp = 84.dp,
) {
    val measurer = rememberTextMeasurer()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 6.dp.toPx()
                drawArc(Lavender, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                drawArc(Purple, -90f, 360f * holdProgress.coerceIn(0f, 1f), false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text(targetName, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(gaugeHeight)) {
            val cx = size.width / 2
            val cy = size.height - 24.dp.toPx()
            val r = min(size.width / 2 - 36.dp.toPx(), cy - 8.dp.toPx())
            drawArc(Lavender, 180f, 180f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
            drawLine(Color(0xFFC9C4F0), Offset(cx - r - 20.dp.toPx(), cy), Offset(cx + r + 20.dp.toPx(), cy), 2f)
            // 반음 눈금(-200 ~ +200 cents → 180° ~ 0°)
            fun angleOf(cents: Float): Double = PI * (1 - (cents + 200f) / 400f)
            listOf(-100f, -50f, 0f, 50f, 100f).forEach { c ->
                val a = angleOf(c)
                drawLine(Color(0xFFB9B2F2), Offset(cx, cy), Offset(cx + (r * cos(a)).toFloat(), cy - (r * sin(a)).toFloat()), 1.5f)
            }
            // 라벨: F3 G♭3 G3 G♯3 A3 (neighborNames 5개)
            val labelCents = listOf(-200f, -100f, 0f, 100f, 200f)
            neighborNames.take(5).forEachIndexed { i, name ->
                val a = angleOf(labelCents[i])
                val rr = r + 22.dp.toPx()
                val layout = measurer.measure(name, TextStyle(fontSize = 15.sp, color = Grey))
                val px = cx + (rr * cos(a)).toFloat() - layout.size.width / 2f
                val py = cy - (rr * sin(a)).toFloat() - layout.size.height / 2f
                drawText(layout, topLeft = Offset(px, py.coerceAtMost(cy - layout.size.height / 2f)))
            }
            centsOffset?.let { c ->
                val a = angleOf(c.coerceIn(-200f, 200f))
                drawLine(if (matched) Teal else Red, Offset(cx, cy), Offset(cx + (r * 0.98f * cos(a)).toFloat(), cy - (r * 0.98f * sin(a)).toFloat()), 5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** 마이크 입력 파형 장식. level 0~1 로 높이를 스케일한다. */
@Composable
fun WaveformBars(level: Float, modifier: Modifier = Modifier, bars: Int = 56) {
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val mid = size.height / 2
        for (i in 0 until bars) {
            val seed = (sin(i * 0.9) * 0.5 + 0.5) * (0.4 + 0.6 * (sin(i * 0.23 + 1.3) * 0.5 + 0.5))
            val h = (size.height * seed * (0.25f + 0.75f * level.coerceIn(0f, 1f))).toFloat().coerceAtLeast(3f)
            drawRoundRect(Purple, Offset(i * (w + gap), mid - h / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** 작은 스피너 대용 원형 아웃라인 (분석 중 표시) */
fun DrawScope.drawRingOutline(color: Color) {
    drawCircle(color, style = Stroke(3f))
}
