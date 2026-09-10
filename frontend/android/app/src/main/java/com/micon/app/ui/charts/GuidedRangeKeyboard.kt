package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeNote
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.theme.*
import kotlin.math.floor
import kotlin.math.ceil

/** Fixed C2–C6 overview using develop's piano graphics; keys never change the test target. */
@Composable
fun GuidedRangeKeyboard(notes: List<RangeNote>, targetMidi: Int? = null, detectedMidi: Double? = null,
                        modifier: Modifier = Modifier, provisional: Boolean = false) {
    val measurer = rememberTextMeasurer()
    fun black(midi: Int) = midi % 12 in setOf(1, 3, 6, 8, 10)
    val whites = (36..84).filterNot(::black)
    val byMidi = notes.associateBy { it.midi }
    val confirmed = notes.count { it.status in setOf("passed", "reconfirmed") }
    val attempted = notes.count { it.status != "unmeasured" }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).testTag("guided-range-keyboard").semantics {
            contentDescription = "C2부터 C6까지 고정 건반, $attempted 개 음 확인, $confirmed 개 음 통과" +
                (targetMidi?.let { ", 목표 ${ScoringMath.midiName(it)}" } ?: "")
        }) {
            val top = 24.dp.toPx()
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
                if (byMidi[midi]?.status in setOf("insufficient_quality", "needs_review")) {
                    drawLine(Orange, Offset(x + w / 2, top + h - 14.dp.toPx()), Offset(x + w / 2, top + h - 7.dp.toPx()), 2.dp.toPx())
                    drawCircle(Orange, 1.dp.toPx(), Offset(x + w / 2, top + h - 3.dp.toPx()))
                }
            }
            whites.forEachIndexed { index, midi -> key(midi, index * width, width, height, false) }
            (36..84).filter(::black).forEach { midi ->
                key(midi, (whites.indexOf(midi - 1) + 1) * width - width * .3f, width * .6f, height * .62f, true)
            }
            listOf(36, 48, 60, 72, 84).forEach { midi ->
                val label = measurer.measure(ScoringMath.midiName(midi), TextStyle(fontSize = 11.sp, color = Grey))
                drawText(label, topLeft = Offset((centers.getValue(midi) - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), 0f))
            }
            targetMidi?.let { centers[it] }?.let { x -> drawCircle(Purple, 3.dp.toPx(), Offset(x, top - 5.dp.toPx())) }
            detectedMidi?.takeIf { it in 36.0..84.0 }?.let { midi ->
                val lo = floor(midi).toInt(); val hi = ceil(midi).toInt()
                val x = centers.getValue(lo) + (centers.getValue(hi) - centers.getValue(lo)) * (midi - lo).toFloat()
                drawCircle(Teal, 4.dp.toPx(), Offset(x, top + height + 12.dp.toPx()))
            }
        }
    }
}
