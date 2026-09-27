package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeStabilityPlot
import com.micon.app.ui.common.RangeNoteNames
import com.micon.app.ui.common.ReportNoteNames
import com.micon.app.ui.common.sharpNoteText
import com.micon.app.ui.theme.*
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

/** Measured points only; dashed curves bridge gaps without assigning scores to missing notes. */
@Composable
fun RangeStabilityChart(plot: RangeStabilityPlot, selectedMidi: Int, onSelect: (Int) -> Unit,
                        modifier: Modifier = Modifier, overview: Boolean = false,
                        scoreLabel: String? = null) {
    val measurer = rememberTextMeasurer()
    val points = plot.points
    if (points.isEmpty()) return
    val low = points.first().note.midi
    val span = (points.last().note.midi - low).coerceAtLeast(1)
    val measure = if (plot.scoreBased) scoreLabel ?: "안정성 점수" else "목표음 유지율"
    val unit = if (plot.scoreBased) "점" else "퍼센트"
    val noteName: (Int) -> String = if (overview) ReportNoteNames::note else RangeNoteNames::full
    val axisName: (Int) -> String = if (overview) ReportNoteNames::note else RangeNoteNames::short
    val label = "음역별 $measure 그래프. 세로축 $measure 0에서 100$unit, 가로축 ${noteName(low)}부터 ${noteName(low + span)}. " +
        when {
            scoreLabel != null -> "실선은 연속 측정 음 사이, 점선은 미측정 음을 지나는 시각적 연결입니다."
            overview -> "측정한 음 사이를 선으로 연결합니다."
            else -> "빈 음은 연결하지 않습니다."
        }
    Canvas(modifier.fillMaxWidth().height(190.dp).testTag("range-stability-chart")
        .semantics {
            contentDescription = label
            customActions = if (overview) emptyList() else
                (if (scoreLabel != null) points.filter { it.ratio != null } else points).map { point ->
                val value = point.ratio?.let { "$measure ${(it * 100).roundToInt()}$unit" } ?: "표시할 측정값 없음"
                CustomAccessibilityAction("${RangeNoteNames.full(point.note.midi)} $value 상세 보기") {
                    onSelect(point.note.midi); true
                }
            }
        }
        .then(if (overview) Modifier else Modifier.pointerInput(plot, onSelect) {
            detectTapGestures { position ->
                val left = 44.dp.toPx()
                val width = (size.width - left - 16.dp.toPx()).coerceAtLeast(1f)
                val midi = low + (((position.x - left) / width).coerceIn(0f, 1f) * span).roundToInt()
                val nearest = if (scoreLabel != null) points.filter { it.ratio != null }
                    .minByOrNull { abs(it.note.midi - midi) }?.note?.midi else null
                onSelect(nearest ?: midi)
            }
        })) {
        val left = 44.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 16.dp.toPx() - 14.sp.toPx()
        fun x(midi: Int) = left + (right - left) * (midi - low) / span
        fun y(ratio: Float) = bottom - (bottom - top) * ratio
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        listOf(1f, .5f, 0f).forEach { value ->
            drawLine(GreyLight, Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx(), pathEffect = dash)
            val text = measurer.measure("${(value * 100).toInt()}" + if (plot.scoreBased) "점" else "%", TextStyle(fontSize = 11.sp, color = Grey))
            drawText(text, topLeft = Offset(left - text.size.width - 8.dp.toPx(), y(value) - text.size.height / 2f))
        }
        val labelStep = ceil(span / if (fontScale > 1.3f) 2.0 else 4.0).toInt().coerceAtLeast(1)
        val ticks = (0..span step labelStep).toMutableList().apply {
            if (last() != span) {
                if (span - last() < labelStep) removeAt(lastIndex)
                add(span)
            }
        }
        ticks.forEach { index ->
            val px = x(low + index)
            drawLine(GreyLight, Offset(px, top), Offset(px, bottom), 1.dp.toPx(), pathEffect = dash)
            val text = measurer.measure(sharpNoteText(axisName(low + index), 11.sp), TextStyle(fontSize = 11.sp, color = Grey))
            drawText(text, topLeft = Offset((px - text.size.width / 2f).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f)), bottom + 12.dp.toPx()))
        }
        drawLine(Grey, Offset(left, top), Offset(left, bottom), 1.dp.toPx())
        val segments = when {
            scoreLabel != null -> plot.connectedSegments
            overview -> points.filter { it.ratio != null }.zipWithNext()
            else -> plot.segments
        }
        segments.forEach { (a, b) ->
            val x0 = x(a.note.midi)
            val x1 = x(b.note.midi)
            val y0 = y(a.ratio!!)
            val y1 = y(b.ratio!!)
            if (scoreLabel != null) {
                val middle = (x0 + x1) / 2f
                val path = Path().apply { moveTo(x0, y0); cubicTo(middle, y0, middle, y1, x1, y1) }
                drawPath(path, Purple, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (b.note.midi - a.note.midi > 1)
                        PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null))
            } else drawLine(Purple, Offset(x0, y0), Offset(x1, y1), 2.dp.toPx())
        }
        points.forEach { point ->
            point.ratio?.let { value ->
                val center = Offset(x(point.note.midi), y(value))
                val color = if (plot.scoreBased && value == 0f) Red else if (point.confirmed) Purple else Red
                drawCircle(GreyLight, if (plot.scoreBased && value == 0f) 6.dp.toPx() else 5.dp.toPx(), center)
                drawCircle(color, if (plot.scoreBased && value == 0f) 4.dp.toPx() else 2.8.dp.toPx(), center)
                if (!overview && point.note.midi == selectedMidi) drawCircle(color, 7.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
        }
        if (points.none { it.ratio != null }) {
            val text = measurer.measure("아직 표시할 측정값이 없어요", TextStyle(fontSize = 13.sp, color = InkSoft))
            drawRect(Paper.copy(alpha = .9f), Offset(left, (top + bottom) / 2 - 18.dp.toPx()),
                androidx.compose.ui.geometry.Size(right - left, 36.dp.toPx()))
            drawText(text, topLeft = Offset(left + (right - left - text.size.width) / 2, (top + bottom - text.size.height) / 2))
        }
    }
}
