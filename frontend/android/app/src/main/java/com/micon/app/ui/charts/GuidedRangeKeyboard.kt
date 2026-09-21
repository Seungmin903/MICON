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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeNote
import com.micon.app.ui.common.RangeNoteNames
import com.micon.app.ui.common.sharpNoteText
import com.micon.app.ui.theme.*
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class GuidedKeyboardMode { FULL_RANGE, THREE_OCTAVES }

internal fun guidedKeyboardRange(focusMidi: Int, mode: GuidedKeyboardMode): IntRange {
    if (mode == GuidedKeyboardMode.FULL_RANGE) return 36..84
    val low = (focusMidi.coerceIn(36, 84) / 12 * 12 - 12).coerceIn(36, 48)
    return low..(low + 36)
}

internal fun guidedKeyboardOctaves(range: IntRange): List<Int> =
    (range.first until range.last step 12).map { it / 12 - 1 }

fun measurementKeyboardMode(phase: String): GuidedKeyboardMode =
    if (phase in setOf("noise", "comfortable_ready", "comfortable", "profile_ready")) GuidedKeyboardMode.FULL_RANGE
    else GuidedKeyboardMode.THREE_OCTAVES

/** Display windows never change the measurement target or the recorded pitch. */
@Composable
fun GuidedRangeKeyboard(notes: List<RangeNote>, targetMidi: Int? = null, detectedMidi: Double? = null,
                        modifier: Modifier = Modifier, provisional: Boolean = false,
                        noteName: (Int) -> String = RangeNoteNames::full,
                        mode: GuidedKeyboardMode = GuidedKeyboardMode.FULL_RANGE,
                        showTargetLabel: Boolean = true,
                        showPassedLegend: Boolean = true,
                        keyboardHeight: Dp = 152.dp) {
    val measurer = rememberTextMeasurer()
    fun black(midi: Int) = midi % 12 in setOf(1, 3, 6, 8, 10)
    val byMidi = notes.associateBy { it.midi }
    val focusMidi = targetMidi ?: detectedMidi?.roundToInt()
        ?: notes.firstOrNull { it.status != "unmeasured" }?.midi ?: 48
    val visibleRange = guidedKeyboardRange(focusMidi, mode)
    val visibleNotes = notes.filter { it.midi in visibleRange }
    val confirmed = visibleNotes.count { it.status in setOf("passed", "reconfirmed") }
    val attempted = visibleNotes.count { it.status != "unmeasured" }
    val whites = visibleRange.filterNot(::black)
    Column(modifier.fillMaxWidth()) {
        targetMidi?.takeIf { showTargetLabel }?.let { midi ->
            Text(sharpNoteText("▼ 지금 부를 음 · ${noteName(midi)}", 15.sp), color = Purple, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("range-current-target"))
            Spacer(Modifier.height(8.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(keyboardHeight).testTag("guided-range-keyboard").semantics {
            stateDescription = guidedKeyboardOctaves(visibleRange).joinToString(", ") { "$it 옥타브" }
            val rangeDescription = if (mode == GuidedKeyboardMode.THREE_OCTAVES) "${noteName(visibleRange.first)}부터 ${noteName(visibleRange.last)}까지 세 옥타브 건반"
                else "${noteName(36)}부터 ${noteName(84)}까지 고정 건반"
            contentDescription = "$rangeDescription, $attempted 개 음 확인, $confirmed 개 음 통과" +
                (if (showPassedLegend) ". 초록 건반과 체크는 통과한 음" else "") +
                (targetMidi?.let { ", 보라 테두리와 화살표는 지금 부를 음 ${noteName(it)}" } ?: "")
        }) {
            val width = size.width / whites.size
            val labels = guidedKeyboardOctaves(visibleRange).map { octave ->
                var style = TextStyle(fontSize = 11.sp, color = Grey)
                var label = measurer.measure("$octave 옥타브", style, softWrap = false)
                val availableWidth = (7 * width - 4.dp.toPx()).coerceAtLeast(1f)
                if (label.size.width > availableWidth) {
                    style = style.copy(fontSize = (11 * availableWidth / label.size.width).sp)
                    label = measurer.measure("$octave 옥타브", style, softWrap = false)
                }
                octave to label
            }
            val top = maxOf(28.dp.toPx(), labels.maxOf { it.second.size.height }.toFloat() + 16.dp.toPx())
            val height = (size.height - top - 24.dp.toPx()).coerceAtLeast(1f)
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
            visibleRange.filter(::black).forEach { midi ->
                key(midi, (whites.indexOf(midi - 1) + 1) * width - width * .3f, width * .6f, height * .62f, true)
            }
            // Octave boundaries precede the target border and input marker so those stay visible.
            (visibleRange.first + 12..visibleRange.last step 12).forEach { midi ->
                val x = whites.indexOf(midi) * width
                drawLine(InkSoft, Offset(x, top), Offset(x, top + height), 2.dp.toPx())
            }
            labels.forEach { (octave, label) ->
                val start = whites.indexOf((octave + 1) * 12) * width
                drawText(label, topLeft = Offset(start + 3.5f * width - label.size.width / 2f, 0f))
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
            detectedMidi?.takeIf { it >= visibleRange.first && it <= visibleRange.last }?.let { midi ->
                val lo = floor(midi).toInt(); val hi = ceil(midi).toInt()
                val x = centers.getValue(lo) + (centers.getValue(hi) - centers.getValue(lo)) * (midi - lo).toFloat()
                drawCircle(Teal, 4.dp.toPx(), Offset(x, top + height + 12.dp.toPx()))
            }
        }
        if (showPassedLegend) Text("✓ 초록 건반은 통과한 음이에요.", color = GreenChipText, fontSize = 12.sp,
            lineHeight = if (showTargetLabel) 24.sp else 16.sp)
    }
}
