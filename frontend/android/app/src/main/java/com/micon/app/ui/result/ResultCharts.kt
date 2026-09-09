package com.micon.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.engine.dto.Comparison
import com.micon.app.data.engine.dto.ComparisonMeasure
import com.micon.app.data.engine.dto.Curves
import com.micon.app.scoring.ScoringMath
import com.micon.app.scoring.ScoringUi
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import com.micon.app.ui.theme.Red
import kotlin.math.roundToInt

/** 점수 요약: 종합·음정·박자 + 판정 개수·평균 센트·평균 편차. `comparison.scores/pitchStats/timingStats/coverage`. */
@Composable
fun ScoreBreakdown(c: Comparison) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigStat("종합", c.scores.overall, c.scores.grade)
            BigStat("음정", c.scores.pitch, null)
            BigStat("박자", c.scores.timing, null)
        }
        val p = c.pitchStats; val t = c.timingStats; val cov = c.coverage
        Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p != null) StatLine("음정 판정", "정확 ${p.good} · 높게 ${p.sharp} · 낮게 ${p.flat} · 다른 음 ${p.wrong} · 놓침 ${p.missed}")
            p?.medianCents?.let { StatLine("음정 경향", if (it > 0) "평균 +${it.roundToInt()}¢ (조금 높게 부르는 편)" else "평균 ${it.roundToInt()}¢ (조금 낮게 부르는 편)") }
            if (t != null) StatLine("박자 판정", "정확 ${t.good} · 빠름 ${t.early} · 늦음 ${t.late} · 어긋남 ${t.off}")
            t?.meanDeviationMs?.let { StatLine("박자 경향", if (it > 0) "평균 ${it.roundToInt()}ms 늦음" else "평균 ${(-it).roundToInt()}ms 빠름") }
            if (cov != null) StatLine("부른 범위", "${cov.notesSung}/${cov.notesTotal} 음표 · 커버 ${((cov.coveredRatio ?: 0.0) * 100).roundToInt()}%")
            c.transposition?.semitones?.takeIf { it != 0 }?.let { StatLine("키", "원곡보다 ${if (it > 0) "+$it" else "$it"} 반음으로 부름") }
            c.scores.stabilityCents?.let { StatLine("음 유지 안정도", "±${it.roundToInt()}¢") }
        }
    }
}

@Composable
private fun BigStat(label: String, value: Double, grade: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Grey)
        Text("${value.roundToInt()}", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(ScoringUi.scoreColor(value)))
        if (grade != null) Text("등급 $grade", fontSize = 12.sp, color = InkSoft, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row { Text(label, fontSize = 12.sp, color = Grey, modifier = Modifier.width(92.dp)); Text(value, fontSize = 12.sp, color = InkSoft, modifier = Modifier.weight(1f)) }
}

/**
 * 마디별 점수 히트맵. 셀 색 = 점수(빨강→주황→초록). 셀을 누르면 그 마디의 가사·점수·판정을 아래에 보여 준다.
 */
@Composable
fun MeasureHeatmap(measures: List<ComparisonMeasure>) {
    if (measures.isEmpty()) { Text("마디별 점수가 없어요.", fontSize = 13.sp, color = Grey); return }
    var selected by remember { mutableStateOf<ComparisonMeasure?>(null) }
    Column {
        // 16열 그리드. 셀은 Compose 레이아웃이라 탭 처리가 간단하다.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            measures.chunked(16).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    rowItems.forEach { m ->
                        val sel = selected?.measure == m.measure
                        Column(
                            Modifier.weight(1f).height(26.dp)
                                .background(Color(ScoringUi.scoreColor(m.score)).copy(alpha = if (m.notes == 0) 0.25f else 1f), RoundedCornerShape(4.dp))
                                .clickable { selected = if (sel) null else m },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("${m.measure}", fontSize = 9.sp, color = Color.White, fontWeight = if (sel) FontWeight.Black else FontWeight.SemiBold)
                        }
                    }
                    repeat(16 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val m = selected
        if (m == null) {
            Text("마디를 누르면 그 부분의 가사와 점수를 볼 수 있어요.", fontSize = 12.sp, color = Grey)
        } else {
            Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text("${m.measure}마디 · ${m.start?.let(ScoringMath::formatTime) ?: ""}  점수 ${m.score.roundToInt()} (음정 ${m.pitch.roundToInt()} · 박자 ${m.timing.roundToInt()})", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                m.lyric?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, color = InkSoft, modifier = Modifier.padding(top = 4.dp)) }
                Text("놓침 ${m.missed} · 높게 ${m.sharp} · 낮게 ${m.flat} · 다른 음 ${m.wrong} · 빠름 ${m.early} · 늦음 ${m.late}", fontSize = 11.sp, color = Grey, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/**
 * 음정 곡선 오버레이: 회색 = 원곡 목표 음정, 보라 = 내 음정. 세로축은 두 곡선의 범위 ±2반음, 가로축은 시간.
 * curves.ref/user 는 hop 간격 MIDI(무성 null). 5분 곡이면 6천 점이라 픽셀당 1점으로 다운샘플한다.
 */
@Composable
fun PitchCurveChart(curves: Curves, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val all = (curves.ref + curves.user).filterNotNull()
    if (all.isEmpty()) { Text("음정 곡선 데이터가 없어요.", fontSize = 13.sp, color = Grey); return }
    val lo = (all.min() - 2).toInt(); val hi = (all.max() + 2).toInt()
    val n = maxOf(curves.ref.size, curves.user.size)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Color(0xFF9A93B8), "원곡 멜로디"); Legend(Purple, "내 음정")
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val leftPad = 30.dp.toPx()
            val w = size.width - leftPad
            val h = size.height - 16.dp.toPx()
            fun x(i: Int) = leftPad + w * i / maxOf(1, n - 1)
            fun y(m: Double) = (h * (hi - m) / maxOf(1, hi - lo)).toFloat()
            // 옥타브 눈금
            for (m in lo..hi) if (m % 12 == 0) {
                drawLine(GreyLight, Offset(leftPad, y(m.toDouble())), Offset(size.width, y(m.toDouble())), 1f)
                drawText(measurer.measure(ScoringMath.midiName(m), TextStyle(fontSize = 10.sp, color = Grey)), topLeft = Offset(0f, y(m.toDouble()) - 6.dp.toPx()))
            }
            fun drawCurve(values: List<Double?>, color: Color, stroke: Float) {
                if (values.isEmpty()) return
                val step = maxOf(1, values.size / w.toInt().coerceAtLeast(1))
                val path = Path(); var open = false
                var i = 0
                while (i < values.size) {
                    val v = values[i]
                    if (v == null) { open = false } else {
                        val px = x(i); val py = y(v)
                        if (!open) { path.moveTo(px, py); open = true } else path.lineTo(px, py)
                    }
                    i += step
                }
                drawPath(path, color, style = Stroke(stroke))
            }
            drawCurve(curves.ref, Color(0xFF9A93B8), 3.5.dp.toPx())
            drawCurve(curves.user, Purple, 1.6.dp.toPx())
            // 시간 눈금 (시작·중간·끝)
            val total = curves.start + n * curves.hop
            listOf(0, n / 2, n - 1).forEach { i ->
                val label = ScoringMath.formatTime(curves.start + i * curves.hop)
                val layout = measurer.measure(label, TextStyle(fontSize = 10.sp, color = Grey))
                drawText(layout, topLeft = Offset((x(i) - layout.size.width / 2f).coerceIn(leftPad, size.width - layout.size.width), h + 2.dp.toPx()))
            }
            @Suppress("UNUSED_VARIABLE") val unused = total
        }
        @Suppress("UNUSED_VARIABLE") val unusedColors = listOf(PurpleMid, Red)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(width = 14.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = Grey)
    }
}
