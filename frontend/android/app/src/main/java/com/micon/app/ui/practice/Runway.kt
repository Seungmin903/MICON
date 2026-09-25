package com.micon.app.ui.practice

import android.graphics.Paint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.micon.app.ui.design.molecules.OCTAVE_SCALE
import com.micon.app.ui.design.molecules.karaokeParts
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
    // 키가 바뀌어도 목표 음표와 세로 음역 창이 같은 시간축을 따라 움직인다.
    // 정수 keyShift를 바로 쓰면 위·아래 경계의 갱신 시점이 달라져 음표가 순간적으로 어긋난다.
    val animatedKeyShift by animateFloatAsState(
        targetValue = keyShift.toFloat(),
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "runwayKeyShift",
    )
    val ink = Color(ScoringUi.COLOR_INK)
    val nowColor = Color(0xFF6C5CE7)
    val runwayBackground = Color(0xFFF7F5FC)

    // 보이는 음표에 맞춰 세로 범위를 움직인다. 중심과 범위를 함께 보간해야 키 변경 시
    // 위·아래 경계가 서로 다른 속도로 움직이며 막대 위치가 틀어지는 현상을 막을 수 있다.
    val view = remember { doubleArrayOf(Double.NaN, Double.NaN) } // center, span
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val dp = density
        val plotBottom = height - 4f * dp
        val visible = bars.filter { it.end >= nowSec - RUNWAY_SECONDS_BEHIND && it.start <= nowSec + RUNWAY_SECONDS_AHEAD }
        val pool = if (visible.size >= 2) visible else bars
        var targetLow = ((pool.minOfOrNull { it.midi } ?: 60) + animatedKeyShift - 2).toDouble()
        var targetHigh = ((pool.maxOfOrNull { it.midi } ?: 72) + animatedKeyShift + 2).toDouble()
        if (targetHigh - targetLow < 11) { val mid = (targetHigh + targetLow) / 2; targetLow = mid - 5.5; targetHigh = mid + 5.5 }
        val traceFrames = frames.takeLast(500).filter {
            it.t >= nowSec - RUNWAY_SECONDS_BEHIND && it.t <= nowSec + RUNWAY_SECONDS_AHEAD
        }
        // 피치는 실제 절대 음높이로 그린다. 한두 옥타브 차이도 잘리지 않도록 현재 창의 유성 음을 축에 포함한다.
        // 지나치게 튄 프레임 하나가 축을 망가뜨리지 않게 목표 음역에서 ±24반음 안만 사용한다.
        val traceMidi = traceFrames.mapNotNull { it.midi }
            .filter { it in (targetLow - 24.0)..(targetHigh + 24.0) }
        targetLow = minOf(targetLow, traceMidi.minOrNull()?.minus(1.0) ?: targetLow)
        targetHigh = maxOf(targetHigh, traceMidi.maxOrNull()?.plus(1.0) ?: targetHigh)
        val targetCenter = (targetLow + targetHigh) / 2.0
        val targetSpan = targetHigh - targetLow
        if (view[0].isNaN()) {
            view[0] = targetCenter
            view[1] = targetSpan
        } else {
            // 키를 올리거나 내릴 때 창 전체가 같은 비율로 이동한다. span도 같이 보간해 줌·늘어남이 없다.
            view[0] += (targetCenter - view[0]) * 0.18
            view[1] += (targetSpan - view[1]) * 0.18
        }
        val low = view[0] - view[1] / 2.0
        val high = view[0] + view[1] / 2.0
        val nowX = width * 0.3f
        val pxPerSecond = width / (RUNWAY_SECONDS_AHEAD + RUNWAY_SECONDS_BEHIND).toFloat()
        fun timeToX(seconds: Double) = nowX + ((seconds - nowSec) * pxPerSecond).toFloat()
        val rowHeight = (plotBottom - 26f * dp) / (high - low + 1).toFloat()
        fun midiToY(midi: Double) = 8f * dp + ((high - midi) * rowHeight).toFloat()
        val barHeight = maxOf(6f * dp, rowHeight * 0.8f)

        drawRect(runwayBackground, size = Size(width, height))

        // 옥타브 선 + 음이름 (노래방식 "2옥 도" — 다른 화면과 같이 "2옥" 은 작게, "도" 는 크게)
        val syllableSize = 10f * dp
        val octaveSize = syllableSize * OCTAVE_SCALE
        for (midi in kotlin.math.ceil(low).toInt()..kotlin.math.floor(high).toInt()) {
            if (midi % 12 != 0 && midi % 12 != 7) continue      // 도·솔 줄만
            val y = midiToY(midi.toDouble()) + rowHeight / 2
            drawLine(ink.copy(alpha = if (midi % 12 == 0) 0.18f else 0.09f), Offset(0f, y), Offset(width, y))
            val (octave, syllable) = karaokeParts(midi, short = true)
            textPaint.textSize = octaveSize
            val octaveW = if (octave.isEmpty()) 0f else textPaint.measureText(octave) + 1.5f * dp
            textPaint.textSize = syllableSize
            val labelW = octaveW + textPaint.measureText(syllable)
            drawRect(runwayBackground.copy(alpha = 0.85f), topLeft = Offset(2f * dp, y - 12f * dp), size = Size(labelW + 4f * dp, 12f * dp))
            textPaint.color = android.graphics.Color.argb(255, 0x9A, 0xA1, 0x9C)
            val baseline = y - 3f * dp
            if (octave.isNotEmpty()) {
                textPaint.textSize = octaveSize
                drawContext.canvas.nativeCanvas.drawText(octave, 4f * dp, baseline, textPaint)
                textPaint.textSize = syllableSize
            }
            drawContext.canvas.nativeCanvas.drawText(syllable, 4f * dp + octaveW, baseline, textPaint)
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
            val y = midiToY(bar.midi + animatedKeyShift.toDouble())
            val color = when (val st = bar.statusOf()) {
                LiveStatus.UPCOMING, LiveStatus.SKIPPED -> Color(ScoringUi.COLOR_UPCOMING)
                LiveStatus.ACTIVE -> ink
                else -> Color(st.toPitchStatus()!!.argb)
            }
            drawRoundRect(color, topLeft = Offset(x0, y - barHeight / 2), size = Size(x1 - x0, barHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2))
        }
        // 내 목소리 궤적 (실제 옥타브 유지) — 보라, 굵게
        val path = Path()
        var open = false
        var previous = Double.NaN
        for (frame in traceFrames) {
            val midi = frame.midi
            if (midi == null) { open = false; previous = Double.NaN; continue }
            if (midi !in (targetLow - 24.0)..(targetHigh + 24.0)) { open = false; previous = Double.NaN; continue }
            // 피치가 갑자기 튀면 선을 끊는다 — 세로로 길게 긋는 잡선 방지
            if (!previous.isNaN() && abs(midi - previous) > 4.0) open = false
            previous = midi
            val x = timeToX(frame.t)
            val y = midiToY(midi)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF6C5CE7), style = Stroke(width = 3f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // 현재 위치선
        drawRect(
            nowColor.copy(alpha = 0.08f),
            topLeft = Offset(nowX - 9f * dp, 0f),
            size = Size(18f * dp, plotBottom - 14f * dp),
        )
        drawRect(
            nowColor.copy(alpha = 0.16f),
            topLeft = Offset(nowX - 5f * dp, 0f),
            size = Size(10f * dp, plotBottom - 14f * dp),
        )
        drawRect(nowColor, topLeft = Offset(nowX - 1f * dp, 0f), size = Size(2f * dp, plotBottom - 14f * dp))
        textPaint.textSize = 10f * dp
        textPaint.color = android.graphics.Color.argb(255, 0x69, 0x73, 0x6C)
        drawContext.canvas.nativeCanvas.drawText(ScoringMath.formatTime(nowSec), nowX + 6f * dp, plotBottom - 3f * dp, textPaint)
    }
}
