package com.micon.app.ui.practice

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.micon.app.data.backend.dto.BeatGrid
import com.micon.app.scoring.DisplayBar
import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.ScoringConstants.RUNWAY_SECONDS_AHEAD
import com.micon.app.scoring.ScoringConstants.RUNWAY_SECONDS_BEHIND
import com.micon.app.scoring.ScoringMath
import com.micon.app.scoring.ScoringUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 러너웨이(피아노롤) — live-scoring.tsx `draw` 의 Canvas 2D 를 Compose Canvas 로. 가사는 아래 가사 목록이 담당하므로
 * 여기서는 그리지 않는다(가사가 크고 작게 두 줄로 겹쳐 보이던 문제). 세로 범위는 지금 보이는 음표에 맞춰 움직여
 * 막대가 크게 보이게 한다. nowSec 이 바뀔 때마다 다시 그린다. frames 는 ViewModel 이 메인 스레드에서만 갱신하는 리스트.
 */
@Composable
fun Runway(
    notes: List<LiveNote>,
    bars: List<DisplayBar>,
    frames: List<Frame>,
    beatGrid: BeatGrid?,
    meter: Int,
    nowSec: Double,
    keyShift: Int,
    modifier: Modifier = Modifier,
) {
    val textPaint = remember { Paint().apply { isAntiAlias = true } }
    val ink = Color(ScoringUi.COLOR_INK)
    val muted = Color(ScoringUi.COLOR_MUTED)
    val nowColor = Color(ScoringUi.COLOR_NOW)

    // 보이는 음표에 맞춰 세로 범위를 움직인다(부드럽게 따라감) — 곡 전체 음역으로 고정하면 막대가 너무 얇아진다
    val view = remember { doubleArrayOf(Double.NaN, Double.NaN) }
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val dp = density
        val plotBottom = height - 4f * dp
        val visible = bars.filter { it.end >= nowSec - RUNWAY_SECONDS_BEHIND && it.start <= nowSec + RUNWAY_SECONDS_AHEAD }
        val pool = if (visible.size >= 2) visible else bars
        var targetLow = ((pool.minOfOrNull { it.midi } ?: 60) + keyShift - 2).toDouble()
        var targetHigh = ((pool.maxOfOrNull { it.midi } ?: 72) + keyShift + 2).toDouble()
        if (targetHigh - targetLow < 11) { val mid = (targetHigh + targetLow) / 2; targetLow = mid - 5.5; targetHigh = mid + 5.5 }
        if (view[0].isNaN()) { view[0] = targetLow; view[1] = targetHigh } else { view[0] += (targetLow - view[0]) * 0.12; view[1] += (targetHigh - view[1]) * 0.12 }
        val low = view[0]
        val high = view[1]
        val nowX = width * 0.3f
        val pxPerSecond = width / (RUNWAY_SECONDS_AHEAD + RUNWAY_SECONDS_BEHIND).toFloat()
        fun timeToX(seconds: Double) = nowX + ((seconds - nowSec) * pxPerSecond).toFloat()
        val rowHeight = (plotBottom - 26f * dp) / (high - low + 1).toFloat()
        fun midiToY(midi: Double) = 8f * dp + ((high - midi) * rowHeight).toFloat()
        val barHeight = maxOf(6f * dp, rowHeight * 0.8f)

        drawRect(Color(ScoringUi.COLOR_PAPER), size = Size(width, height))

        // 옥타브 선 + 음이름 (노래방식: 2옥 도)
        textPaint.textSize = 10f * dp
        for (midi in kotlin.math.ceil(low).toInt()..kotlin.math.floor(high).toInt()) {
            if (midi % 12 != 0 && midi % 12 != 7) continue      // 도·솔 줄만
            val y = midiToY(midi.toDouble()) + rowHeight / 2
            drawLine(ink.copy(alpha = if (midi % 12 == 0) 0.18f else 0.09f), Offset(0f, y), Offset(width, y))
            val label = com.micon.app.scoring.KaraokeNames.koreanShort(midi)
            val labelW = textPaint.measureText(label)
            drawRect(Color(ScoringUi.COLOR_PAPER).copy(alpha = 0.85f), topLeft = Offset(2f * dp, y - 12f * dp), size = Size(labelW + 4f * dp, 12f * dp))
            textPaint.color = android.graphics.Color.argb(255, 0x9A, 0xA1, 0x9C)
            drawContext.canvas.nativeCanvas.drawText(label, 4f * dp, y - 3f * dp, textPaint)
        }
        // 마디선
        beatGrid?.let { grid ->
            for (index in grid.beatTimes.indices) {
                val beat = index - grid.offsetBeats
                val time = grid.beatTimes[index]
                if (beat < 0 || abs(beat % meter) > 1e-6) continue
                if (time < nowSec - RUNWAY_SECONDS_BEHIND || time > nowSec + RUNWAY_SECONDS_AHEAD) continue
                val x = timeToX(time)
                drawLine(ink.copy(alpha = 0.15f), Offset(x, 0f), Offset(x, plotBottom - 14f * dp))
                drawContext.canvas.nativeCanvas.drawText("${(beat / meter).roundToInt() + 1}", x + 3f * dp, 10f * dp, textPaint)
            }
        }
        // 음표 막대: 음절 단위로 이어 붙인 DisplayBar (둥근 모서리, 두껍게)
        for (bar in visible) {
            val x0 = timeToX(bar.start)
            val x1 = maxOf(x0 + 6f * dp, timeToX(bar.end) - 2f * dp)
            val y = midiToY((bar.midi + keyShift).toDouble())
            val color = when (val st = bar.statusOf()) {
                LiveStatus.UPCOMING, LiveStatus.SKIPPED -> Color(ScoringUi.COLOR_UPCOMING)
                LiveStatus.ACTIVE -> ink
                else -> Color(st.toPitchStatus()!!.argb)
            }
            drawRoundRect(color, topLeft = Offset(x0, y - barHeight / 2), size = Size(x1 - x0, barHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2))
        }
        // 내 목소리 궤적 (옥타브는 목표 근처로 폴딩) — 보라, 굵게
        val path = Path()
        var open = false
        var anchor = (low + high) / 2.0
        var previous = Double.NaN
        val from = maxOf(0, frames.size - 500)
        for (i in from until frames.size) {
            val frame = frames[i]
            if (frame.t < nowSec - RUNWAY_SECONDS_BEHIND) continue
            val midi = frame.midi
            if (midi == null) { open = false; previous = Double.NaN; continue }
            val active = bars.firstOrNull { it.start - 0.1 <= frame.t && frame.t <= it.end + 0.1 }
            if (active != null) anchor = (active.midi + keyShift).toDouble()
            val displayed = anchor + ScoringMath.fold(midi - anchor)
            // 옥타브 접기 기준(anchor)이 바뀌거나 피치가 크게 튀면 선을 끊는다 — 세로로 길게 긋는 잡선 방지
            if (!previous.isNaN() && abs(displayed - previous) > 4.0) open = false
            previous = displayed
            val x = timeToX(frame.t)
            val y = midiToY(displayed)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF6C5CE7), style = Stroke(width = 3f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // 현재 위치선
        drawRect(nowColor, topLeft = Offset(nowX - 1f * dp, 0f), size = Size(2f * dp, plotBottom - 14f * dp))
        drawRect(ink, topLeft = Offset(nowX - 1f * dp, 0f), size = Size(2f * dp, plotBottom - 14f * dp), style = Stroke(width = 1f))
        textPaint.textSize = 10f * dp
        textPaint.color = android.graphics.Color.argb(255, 0x69, 0x73, 0x6C)
        drawContext.canvas.nativeCanvas.drawText(ScoringMath.formatTime(nowSec), nowX + 6f * dp, plotBottom - 3f * dp, textPaint)
        @Suppress("UNUSED_VARIABLE") val unused = muted
    }
}
