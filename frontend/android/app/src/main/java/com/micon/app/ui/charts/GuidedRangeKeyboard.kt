package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeNote
import com.micon.app.ui.common.RangeNoteNames
import com.micon.app.ui.theme.*
import kotlin.math.floor
import kotlin.math.ceil

/** Fixed C2–C6 overview using develop's piano graphics; keys never change the test target. */
@Composable
fun GuidedRangeKeyboard(notes: List<RangeNote>, targetMidi: Int? = null, detectedMidi: Double? = null,
                        modifier: Modifier = Modifier, provisional: Boolean = false,
                        labelOf: (Int) -> String = RangeNoteNames::short,
                        noteName: (Int) -> String = RangeNoteNames::full) {
    val measurer = rememberTextMeasurer()
    fun black(midi: Int) = midi % 12 in setOf(1, 3, 6, 8, 10)
    val whites = (36..84).filterNot(::black)
    val byMidi = notes.associateBy { it.midi }
    val confirmed = notes.count { it.status in setOf("passed", "reconfirmed") }
    val attempted = notes.count { it.status != "unmeasured" }
    Column(modifier.fillMaxWidth()) {
        targetMidi?.let { midi ->
            Text("▼ 지금 부를 음 · ${noteName(midi)}", color = Purple, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("range-current-target"))
            Spacer(Modifier.height(8.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(152.dp).testTag("guided-range-keyboard").semantics {
            contentDescription = "${noteName(36)}부터 ${noteName(84)}까지 고정 건반, $attempted 개 음 확인, $confirmed 개 음 통과. 초록 건반과 체크는 통과한 음" +
                (targetMidi?.let { ", 보라 테두리와 화살표는 지금 부를 음 ${noteName(it)}" } ?: "")
        }) {
            val top = 36.dp.toPx()
            val height = size.height - top - 24.dp.toPx()
            val width = size.width / whites.size
            val centers = mutableMapOf<Int, Float>()
            fun fill(midi: Int, isBlack: Boolean) = when (byMidi[midi]?.status) {
                "passed", "reconfirmed" -> GreenBar
                "not_passed", "inconsistent" -> RedChipBg
                else -> if (isBlack) Ink else Paper
            }
            fun key(midi: Int, x: Float, w: Float, h: Float, isBlack: Boolean) {
                centers[midi] = x + w / 2
                drawRect(fill(midi, isBlack), Offset(x, top), Size(w, h))
                drawRect(if (midi == targetMidi) Purple else Grey, Offset(x, top), Size(w, h),
                    style = Stroke(if (midi == targetMidi) 2.dp.toPx() else .5.dp.toPx()))
                if (byMidi[midi]?.status in setOf("passed", "reconfirmed")) {
                    val check = Path().apply {
                        moveTo(x + w * .2f, top + h - 12.dp.toPx())
                        lineTo(x + w * .45f, top + h - 9.dp.toPx())
                        lineTo(x + w * .8f, top + h - 15.dp.toPx())
                    }
                    drawPath(check, GreenChipText, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
                if (byMidi[midi]?.status in setOf("insufficient_quality", "needs_review")) {
                    drawLine(Orange, Offset(x + w / 2, top + h - 14.dp.toPx()), Offset(x + w / 2, top + h - 7.dp.toPx()), 2.dp.toPx())
                    drawCircle(Orange, 1.dp.toPx(), Offset(x + w / 2, top + h - 3.dp.toPx()))
                }
            }
            whites.forEachIndexed { index, midi -> key(midi, index * width, width, height, false) }
            (36..84).filter(::black).forEach { midi ->
                key(midi, (whites.indexOf(midi - 1) + 1) * width - width * .3f, width * .6f, height * .62f, true)
            }
            (if (fontScale > 1.3f) listOf(36, 60, 84) else listOf(36, 48, 60, 72, 84)).forEach { midi ->
                val label = measurer.measure(labelOf(midi), TextStyle(fontSize = 11.sp, color = Grey))
                drawText(label, topLeft = Offset((centers.getValue(midi) - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), 0f))
            }
            targetMidi?.takeIf { it in centers }?.let { midi ->
                val w = if (black(midi)) width * .6f else width
                val h = if (black(midi)) height * .62f else height
                val center = centers.getValue(midi)
                // Draw the current target last so adjacent black keys cannot hide its border.
                drawRect(Purple, Offset(center - w / 2, top), Size(w, h), style = Stroke(2.dp.toPx()))
                val x = center.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx())
                val arrow = Path().apply {
                    moveTo(x - 5.dp.toPx(), top - 13.dp.toPx())
                    lineTo(x + 5.dp.toPx(), top - 13.dp.toPx())
                    lineTo(x, top - 5.dp.toPx())
                    close()
                }
                drawPath(arrow, Purple)
            }
            detectedMidi?.takeIf { it in 36.0..84.0 }?.let { midi ->
                val lo = floor(midi).toInt(); val hi = ceil(midi).toInt()
                val x = centers.getValue(lo) + (centers.getValue(hi) - centers.getValue(lo)) * (midi - lo).toFloat()
                drawCircle(Teal, 4.dp.toPx(), Offset(x, top + height + 12.dp.toPx()))
            }
        }
        Text("✓ 초록 건반은 통과한 음이에요.", color = GreenChipText, fontSize = 12.sp)
    }
}
