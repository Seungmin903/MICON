package com.micon.app.ui.songdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.RangeNoteNames
import com.micon.app.ui.songdetail.SongDetailColors as Detail
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongPitchCard(pitch: SongPitchPresentation, selected: PitchRegion?, positionSec: Double,
    playable: Boolean, loading: Boolean, onSelect: (PitchRegion) -> Unit, onListen: () -> Unit) {
    MiconCard(Modifier.testTag("song-pitch-card"), color = Detail.card) {
        CardTitle("노래 높낮이 흐름", color = Detail.title)
        Spacer(Modifier.height(6.dp))
        Text("부담되는 구간을 누르면 자세히 볼 수 있어요", color = Detail.muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PitchLegend(Detail.accent, if (pitch.selectedKey == 0) "원키 멜로디" else "${songKeyLabel(pitch.selectedKey)} 적용")
            if (pitch.selectedKey != 0) PitchLegend(Detail.measured, "원키 비교 · 점선")
            if (pitch.range != null) PitchLegend(Detail.measured, "내 측정 음역")
            if (pitch.personalRegions.isNotEmpty()) PitchLegend(Detail.warning, "음역 밖")
            if (pitch.regions.any { it.kind == PitchRegionKind.SONG }) PitchLegend(Detail.songWarning, "곡 주의 구간")
        }
        Spacer(Modifier.height(10.dp))
        if (pitch.notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp).background(Detail.inset, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(if (loading) "멜로디를 불러오고 있어요…" else "음표가 준비되면 높낮이 흐름을 볼 수 있어요", color = Detail.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        } else {
            SongPitchGraph(pitch, selected, positionSec, onSelect)
            Spacer(Modifier.height(8.dp))
            Text(when { pitch.range == null -> "목소리 분석 후 내 음역 밖 구간도 표시돼요."
                pitch.personalRegions.isEmpty() -> "${songKeyLabel(pitch.selectedKey)} 기준, 내 측정 음역을 벗어나는 음표가 없어요."
                else -> "${songKeyLabel(pitch.selectedKey)} 기준 · 음역 밖 ${pitch.personalRegions.size}구간" },
                fontSize = 12.sp, color = if (pitch.personalRegions.isEmpty()) Detail.muted else Detail.accent, lineHeight = 18.sp)
        }
        if (pitch.regions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pitch.regions.forEachIndexed { index, region ->
                    FilterChip(selected = selected?.id == region.id, onClick = { onSelect(region) },
                        label = { Text("${index + 1} · ${songTime(region.start)}", fontSize = 12.sp) },
                        modifier = Modifier.testTag("pitch-region-$index").semantics { contentDescription = "${region.title}, ${songTime(region.start)}부터 ${songTime(region.end)}" })
                }
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().background(if (selected.kind == PitchRegionKind.SONG) Detail.inset else Detail.rangeBand, RoundedCornerShape(14.dp)).padding(14.dp).testTag("pitch-region-detail")) {
                Text(selected.title, color = Detail.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp)); Text("${songTime(selected.start)} – ${songTime(selected.end)}", color = Detail.accent, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp)); Text(selected.description, color = Detail.body, fontSize = 13.sp, lineHeight = 20.sp)
                TextButton(onClick = onListen, enabled = playable, contentPadding = PaddingValues(top = 8.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("이 위치 듣기")
                }
            }
        }
    }
}

@Composable
private fun PitchLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(5.dp)); Text(label, fontSize = 10.sp, color = Detail.muted)
    }
}

/** Original note extrema and gaps are preserved; all regions use the same full-song clock. */
@Composable
private fun SongPitchGraph(pitch: SongPitchPresentation, selected: PitchRegion?, positionSec: Double, onSelect: (PitchRegion) -> Unit) {
    val text = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(244.dp).testTag("song-pitch-graph")
        .semantics { contentDescription = "곡 시간별 음 높이 그래프. 아래 구간 버튼으로 상세 내용을 확인할 수 있습니다." }
        .pointerInput(pitch) {
            detectTapGestures { point ->
                val left = 44.dp.toPx(); val width = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
                val time = ((point.x - left) / width).coerceIn(0f, 1f) * pitch.duration
                val tolerance = 12.dp.toPx() / width * pitch.duration
                pitch.regions.filter { time >= it.start - tolerance && time <= it.end + tolerance }
                    .minByOrNull { abs(time - (it.start + it.end) / 2) }?.let(onSelect)
            }
        }) {
        val left = 44.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 24.dp.toPx(); val bottom = size.height - 26.dp.toPx()
        fun x(t: Double) = left + (t / pitch.duration.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) * (right - left)
        fun y(m: Int) = top + (pitch.axisHigh - m).toFloat() / (pitch.axisHigh - pitch.axisLow).coerceAtLeast(1) * (bottom - top)
        pitch.range?.let { r -> drawRect(Detail.rangeBand, Offset(left, y(r.high)), Size(right - left, (y(r.low) - y(r.high)).coerceAtLeast(2.dp.toPx()))) }
        val step = if (pitch.axisHigh - pitch.axisLow > 30) 12 else 7
        for (m in pitch.axisLow..pitch.axisHigh) {
            if ((m - pitch.axisLow) % step != 0) continue
            drawLine(Detail.track.copy(alpha = .6f), Offset(left, y(m)), Offset(right, y(m)), 1.dp.toPx())
            val label = text.measure(RangeNoteNames.short(m), TextStyle(fontSize = 9.sp, color = Detail.muted))
            drawText(label, topLeft = Offset(0f, y(m) - label.size.height / 2))
        }
        pitch.regions.forEachIndexed { index, region ->
            val color = if (region.kind == PitchRegionKind.SONG) Detail.songWarning else Detail.warning
            val start = x(region.start); val width = (x(region.end) - start).coerceAtLeast(2.dp.toPx()).coerceAtMost(right - start)
            if (region.kind == PitchRegionKind.SONG) drawRect(color, Offset(start, top - 5.dp.toPx()), Size(width, 3.dp.toPx()))
            else drawRect(color.copy(alpha = if (selected?.id == region.id) .22f else .10f), Offset(start, top), Size(width, bottom - top))
            // Number only the selected region, avoiding overlapping labels for dense songs.
            if (selected?.id == region.id) {
                drawLine(color, Offset(start, top), Offset(start, bottom), 1.5.dp.toPx())
                val label = text.measure("${index + 1}", TextStyle(fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold))
                drawText(label, topLeft = Offset(start.coerceIn(left, (right - label.size.width).coerceAtLeast(left)), 0f))
            }
        }
        fun line(shift: Int, color: Color, dashed: Boolean) {
            val path = Path()
            var lastEnd = -10.0
            pitch.notes.forEach { note ->
                val a = x(note.sourceStart); val b = x(note.sourceEnd); val level = y(note.midi + shift)
                if (note.sourceStart - lastEnd > 1.0) path.moveTo(a, level) else path.lineTo(a, level)
                path.lineTo(b, level); lastEnd = note.sourceEnd
            }
            drawPath(path, color, style = Stroke(if (dashed) 1.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx())) else null))
        }
        if (pitch.selectedKey != 0) line(0, Detail.measured.copy(alpha = .6f), true)
        line(pitch.selectedKey, Detail.accent, false)
        pitch.notes.maxByOrNull { it.midi }?.let { peak ->
            drawCircle(Detail.accent, 4.dp.toPx(), Offset(x(peak.sourceStart), y(peak.midi + pitch.selectedKey)))
        }
        if (positionSec > 0) drawLine(Detail.title.copy(alpha = .6f), Offset(x(positionSec), top), Offset(x(positionSec), bottom), 1.dp.toPx())
        for (i in 0..4) {
            val time = pitch.duration * i / 4
            val label = text.measure(songTime(time), TextStyle(fontSize = 10.sp, color = Detail.muted))
            drawText(label, topLeft = Offset((x(time) - label.size.width / 2).coerceIn(left, (right - label.size.width).coerceAtLeast(left)), bottom + 8.dp.toPx()))
        }
    }
}
