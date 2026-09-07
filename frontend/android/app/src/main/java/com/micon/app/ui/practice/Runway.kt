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
import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.LyricLine
import com.micon.app.scoring.ScoringConstants.RUNWAY_SECONDS_AHEAD
import com.micon.app.scoring.ScoringConstants.RUNWAY_SECONDS_BEHIND
import com.micon.app.scoring.ScoringMath
import com.micon.app.scoring.ScoringUi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 러너웨이 + 가사 스트립 (live-scoring.tsx `draw` L448–598 의 Canvas 2D 를 Compose Canvas 로).
 * nowSec 이 바뀔 때마다 다시 그린다. frames 는 ViewModel 이 메인 스레드에서만 갱신하는 리스트.
 */
@Composable
fun Runway(
    notes: List<LiveNote>,
    frames: List<Frame>,
    lyricLines: List<LyricLine>,
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

    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val dp = density
        val lyricStrip = 44f * dp
        val plotBottom = height - lyricStrip
        val midis = notes.map { it.midi + keyShift }
        val low = (midis.minOrNull() ?: 60) - 3
        val high = (midis.maxOrNull() ?: 72) + 3
        val nowX = width * 0.3f
        val pxPerSecond = width / (RUNWAY_SECONDS_AHEAD + RUNWAY_SECONDS_BEHIND).toFloat()
        fun timeToX(seconds: Double) = nowX + ((seconds - nowSec) * pxPerSecond).toFloat()
        val rowHeight = (plotBottom - 26f * dp) / (high - low + 1)
        fun midiToY(midi: Double) = 8f * dp + ((high - midi) * rowHeight).toFloat()

        drawRect(Color(ScoringUi.COLOR_PAPER), size = Size(width, height))

        // 옥타브 선 + 음이름
        textPaint.textSize = 9f * dp
        for (midi in low..high) {
            if (midi % 12 != 0) continue
            val y = midiToY(midi.toDouble()) + rowHeight / 2
            drawLine(ink.copy(alpha = 0.18f), Offset(0f, y), Offset(width, y))
            textPaint.color = android.graphics.Color.argb(255, 0x9A, 0xA1, 0x9C)
            drawContext.canvas.nativeCanvas.drawText(ScoringMath.midiName(midi), 4f * dp, y - 3f * dp, textPaint)
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
        // 음표
        for (note in notes) {
            if (note.end < nowSec - RUNWAY_SECONDS_BEHIND || note.start > nowSec + RUNWAY_SECONDS_AHEAD) continue
            val x0 = timeToX(note.start)
            val x1 = maxOf(x0 + 3f * dp, timeToX(note.end) - 1f * dp)
            val y = midiToY((note.midi + keyShift).toDouble())
            val color = when (note.status) {
                LiveStatus.UPCOMING -> Color(ScoringUi.COLOR_UPCOMING)
                LiveStatus.ACTIVE -> ink
                else -> Color(note.status.toPitchStatus()!!.argb)
            }
            drawRect(color, topLeft = Offset(x0, y - rowHeight * 0.42f), size = Size(x1 - x0, rowHeight * 0.84f))
        }
        // 내 목소리 궤적 (옥타브는 목표 근처로 폴딩)
        val path = Path()
        var open = false
        var anchor = (low + high) / 2.0
        val from = maxOf(0, frames.size - 500)
        for (i in from until frames.size) {
            val frame = frames[i]
            if (frame.t < nowSec - RUNWAY_SECONDS_BEHIND) continue
            val midi = frame.midi
            if (midi == null) { open = false; continue }
            val active = notes.firstOrNull { it.start - 0.1 <= frame.t && frame.t <= it.end + 0.1 }
            if (active != null) anchor = (active.midi + keyShift).toDouble()
            val displayed = anchor + ScoringMath.fold(midi - anchor)
            val x = timeToX(frame.t)
            val y = midiToY(displayed)
            if (!open) { path.moveTo(x, y); open = true } else path.lineTo(x, y)
        }
        drawPath(path, ink, style = Stroke(width = 2f * dp))
        // 현재 위치선
        drawRect(nowColor, topLeft = Offset(nowX - 1f * dp, 0f), size = Size(2f * dp, plotBottom - 14f * dp))
        drawRect(ink, topLeft = Offset(nowX - 1f * dp, 0f), size = Size(2f * dp, plotBottom - 14f * dp), style = Stroke(width = 1f))
        textPaint.textSize = 10f * dp
        textPaint.color = android.graphics.Color.argb(255, 0x69, 0x73, 0x6C)
        drawContext.canvas.nativeCanvas.drawText(ScoringMath.formatTime(nowSec), nowX + 6f * dp, plotBottom - 3f * dp, textPaint)

        // 가사 스트립: 현재 줄 + 다음 줄 미리보기
        drawRect(Color(ScoringUi.COLOR_STRIP), topLeft = Offset(0f, plotBottom), size = Size(width, lyricStrip))
        drawLine(ink.copy(alpha = 0.15f), Offset(0f, plotBottom + 0.5f), Offset(width, plotBottom + 0.5f))
        val lineIndex = lyricLines.indexOfFirst { it.end >= nowSec - 0.15 }
        val currentLine = lyricLines.getOrNull(lineIndex)
        if (currentLine != null) {
            var fontSize = 19f * dp
            textPaint.textSize = fontSize
            textPaint.isFakeBoldText = true
            val full = currentLine.text
            val maxWidth = width - 32f * dp
            var total = textPaint.measureText(full)
            if (total > maxWidth) {
                fontSize = maxOf(12f * dp, (fontSize * maxWidth / total))
                textPaint.textSize = fontSize
                total = textPaint.measureText(full)
            }
            val spaceWidth = textPaint.measureText(" ")
            var x = (width - total) / 2
            val y = plotBottom + 16f * dp + fontSize * 0.35f
            for (token in currentLine.tokens) {
                if (token.space) x += spaceWidth
                val note = token.note
                val lyric = note.lyric ?: continue
                val textWidth = textPaint.measureText(lyric)
                val active = note.start - 0.05 <= nowSec && nowSec <= note.end + 0.05
                if (active) drawRect(nowColor, topLeft = Offset(x - 2f * dp, y - fontSize * 0.97f), size = Size(textWidth + 4f * dp, fontSize * 1.24f))
                val argb = when {
                    active -> ScoringUi.COLOR_INK
                    note.status.judged -> note.status.toPitchStatus()!!.argb
                    note.end < nowSec -> ScoringUi.COLOR_INK
                    else -> ScoringUi.COLOR_MUTED
                }
                textPaint.color = argb.toInt()
                drawContext.canvas.nativeCanvas.drawText(lyric, x, y, textPaint)
                x += textWidth
            }
            textPaint.isFakeBoldText = false
            lyricLines.getOrNull(lineIndex + 1)?.let { next ->
                textPaint.textSize = 12f * dp
                textPaint.color = ScoringUi.COLOR_MUTED_2.toInt()
                val preview = next.text
                drawContext.canvas.nativeCanvas.drawText(preview, (width - textPaint.measureText(preview)) / 2, plotBottom + lyricStrip - 6f * dp, textPaint)
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = muted
    }
}
