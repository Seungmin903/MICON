package com.micon.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.theme.*
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Each audio second completes one circle; the next color fills over the completed circle. */
@Composable
fun PitchHoldRing(targetName: String, holdSeconds: Float, modifier: Modifier = Modifier, ringSize: Dp = 64.dp,
                  holdLabel: String = "연속 유지") {
    val seconds = if (holdSeconds.isFinite()) holdSeconds.coerceAtLeast(0f) else 0f
    val completed = floor(seconds).toInt()
    val fraction = seconds - completed
    val colors = listOf(Purple, Teal, Orange)
    val trackColor = if (completed == 0) Lavender else colors[(completed - 1) % colors.size]
    val arcColor = colors[completed % colors.size]
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val baseStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    // Use the same fitted font for every supported note, including sharps and the octave.
    val widestName = remember(measurer, density.density, density.fontScale) {
        (36..84).map(ScoringMath::midiName).maxBy { measurer.measure(it, baseStyle, softWrap = false, maxLines = 1).size.width }
    }
    BoxWithConstraints(modifier.size(ringSize).testTag("pitch-hold-ring").semantics {
        contentDescription = "목표음 $targetName"
        stateDescription = "$holdLabel ${String.format(Locale.ROOT, "%.2f", seconds)}초 · 완료한 원 ${completed}개 · 다음 원 ${(fraction * 100).roundToInt()}퍼센트"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val origin = Offset(stroke / 2, stroke / 2)
            val bounds = Size(size.width - stroke, size.height - stroke)
            drawArc(trackColor, -90f, 360f, false, origin, bounds, style = Stroke(stroke))
            if (fraction > 0f) drawArc(arcColor, -90f, fraction * 360f, false, origin, bounds,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val availableWidth = with(density) { (maxWidth - 24.dp).toPx() }.coerceAtLeast(1f)
        val availableHeight = with(density) { (maxHeight - 24.dp).toPx() }.coerceAtLeast(1f)
        val fontSize = remember(measurer, widestName, availableWidth, availableHeight, density.fontScale) {
            // Measure each candidate: Android's large-font scaling is not always linear.
            var low = 1f
            var high = 32f
            repeat(10) {
                val candidate = (low + high) / 2
                val size = measurer.measure(widestName, baseStyle.copy(fontSize = candidate.sp), softWrap = false, maxLines = 1).size
                if (size.width <= availableWidth - 2 && size.height <= availableHeight - 2) low = candidate else high = candidate
            }
            low.sp
        }
        Text(targetName, modifier = Modifier.width((maxWidth - 24.dp).coerceAtLeast(1.dp)).testTag("pitch-target-note"),
            style = baseStyle.copy(fontSize = fontSize, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
    }
}
