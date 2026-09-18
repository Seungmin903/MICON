package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
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



@Composable
fun GuidedRangeMapChart(
    rows: List<RangeRow>,
    modifier: Modifier = Modifier,
    axisLow: Int = 48,
    axisHigh: Int = 72,
    ticks: List<Int> = listOf(48, 55, 60, 67, 72),
    labelWidth: Dp = 84.dp,
    noteCells: Boolean = false,
    labelOf: (Int) -> String = { ScoringMath.midiName(it) },
) {
    val measurer = rememberTextMeasurer()
    val axisLabelHeight = with(LocalDensity.current) { 18.sp.toDp() }
    val rowHeight = with(LocalDensity.current) { 18.sp.toDp() + 12.dp }
    val grouped = rows.groupBy { it.label }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(axisLabelHeight)) {
            Spacer(Modifier.width(labelWidth))
            Canvas(Modifier.weight(1f).fillMaxSize()) {
                val span = (axisHigh - axisLow + if (noteCells) 1 else 0).toFloat()
                ticks.forEach { m ->
                    val x = size.width * (m - axisLow + if (noteCells) .5f else 0f) / span
                    val layout = measurer.measure(labelOf(m), TextStyle(fontSize = 11.sp, color = Grey))
                    drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), 0f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        grouped.forEach { (label, segs) ->
            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = Ink,
                    modifier = Modifier.width(labelWidth - 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                Spacer(Modifier.width(10.dp))
                Canvas(Modifier.weight(1f).fillMaxSize()) {
                    val span = (axisHigh - axisLow + if (noteCells) 1 else 0).toFloat()
                    val y = size.height / 2f
                    drawLine(GreyLight, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                    val barH = 8.dp.toPx()
                    segs.forEach { s ->
                        val x0 = size.width * (s.lowMidi - axisLow + if (noteCells) .15f else 0f).coerceAtLeast(0f) / span
                        val x1 = size.width * (s.highMidi - axisLow + if (noteCells) .85f else 0f).coerceAtMost(span) / span
                        val width = if (noteCells) max(0f, x1 - x0) else max(x1 - x0, barH)
                        drawRoundRect(s.color, Offset(x0, y - barH / 2), Size(width, barH), CornerRadius(min(width, barH) / 2))
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

@Composable
fun GuidedRingProgress(percent: Int?, modifier: Modifier = Modifier, size: Dp = 104.dp, stroke: Dp = 10.dp, caption: String = "종합 적합도") {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val rect = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(Lavender, 0f, 360f, false, rect, sz, style = Stroke(sw))
            if (percent != null) drawArc(Purple, -90f, 360f * percent / 100f, false, rect, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percent?.toString() ?: "—", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (percent != null) Text("%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Text(caption, fontSize = 10.sp, color = Grey)
        }
    }
}

@Composable
fun GuidedMetricBar(label: String, value: Float?, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(36.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(Lavender, cornerRadius = CornerRadius(size.height / 2))
            if (value != null) drawRoundRect(Purple, size = Size(size.width * value.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp)) }
    }
}

@Composable
fun GuidedRadarHexagon(values: List<Float>?, labels: List<String>, modifier: Modifier = Modifier) {
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
        if (values != null) {
            val poly = Path()
            values.take(6).forEachIndexed { i, v -> val o = pt(i, v.coerceIn(0.05f, 1f)); if (i == 0) poly.moveTo(o.x, o.y) else poly.lineTo(o.x, o.y) }
            poly.close()
            drawPath(poly, Brush.verticalGradient(listOf(PurpleMid.copy(alpha = 0.75f), Purple.copy(alpha = 0.35f))))
            drawPath(poly, Purple, style = Stroke(2f))
        }
        labels.take(6).forEachIndexed { i, label ->
            val o = pt(i, 1.28f)
            val layout = measurer.measure(label, TextStyle(fontSize = 11.sp, color = Ink))
            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
        }
    }
}

@Composable
fun GuidedPitchGauge(
    targetName: String,
    neighborNames: List<String>,
    centsOffset: Float?,
    matched: Boolean,
    holdSeconds: Float,
    modifier: Modifier = Modifier,
    gaugeHeight: Dp = 190.dp,
    ringSize: Dp = 84.dp,
    needleColor: Color = if (matched) Teal else Red,
    holdLabel: String = "연속 유지",
    compact: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PitchHoldRing(targetName, holdSeconds, ringSize = ringSize, holdLabel = holdLabel)
        Spacer(Modifier.height(if (compact) 2.dp else 12.dp))
        val labelLayouts = neighborNames.take(5).map { measurer.measure(it, TextStyle(fontSize = 15.sp, color = Grey), softWrap = false, maxLines = 1) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelHeight = with(LocalDensity.current) { (labelLayouts.maxOfOrNull { it.size.height } ?: 0).toDp() }
            val chartHeight = if (compact) minOf(gaugeHeight, (maxWidth / 2 - 36.dp + labelHeight + 32.dp).coerceAtLeast(80.dp))
                else gaugeHeight
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                val cx = size.width / 2
                val cy = size.height - 24.dp.toPx()
                val labelHeight = labelLayouts.maxOfOrNull { it.size.height } ?: 0
                val r = min(size.width / 2 - 36.dp.toPx(), cy - labelHeight - 8.dp.toPx()).coerceAtLeast(1f)
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
                labelLayouts.forEachIndexed { i, layout ->
                    val a = angleOf(labelCents[i])
                    val rr = r + 22.dp.toPx()
                    val px = cx + (rr * cos(a)).toFloat() - layout.size.width / 2f
                    val py = cy - (rr * sin(a)).toFloat() - layout.size.height / 2f
                    drawText(layout, topLeft = Offset(px.coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)),
                        py.coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))))
                }
                centsOffset?.let { c ->
                    val a = angleOf(c.coerceIn(-200f, 200f))
                    drawLine(needleColor, Offset(cx, cy), Offset(cx + (r * 0.98f * cos(a)).toFloat(), cy - (r * 0.98f * sin(a)).toFloat()), 5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
