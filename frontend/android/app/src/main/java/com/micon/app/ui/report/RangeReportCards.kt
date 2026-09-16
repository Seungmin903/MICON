package com.micon.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeNote
import com.micon.app.range.RangeStabilityPlot
import com.micon.app.range.continuousRangeMap
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.RangeStabilityChart
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.charts.GuidedRangeMapChart
import com.micon.app.ui.charts.RangeRow
import com.micon.app.ui.charts.PhraseSegments
import com.micon.app.ui.charts.BpmGaugeRow
import com.micon.app.ui.charts.GuidedRingProgress
import com.micon.app.ui.charts.GuidedMetricBar
import com.micon.app.ui.charts.GuidedRadarHexagon
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.theme.*
import java.util.Locale

private fun noteStatus(status: String) = when (status) {
    "passed" -> "통과"
    "reconfirmed" -> "반복 확인"
    "inconsistent" -> "재현 불안정"
    "not_passed" -> "미통과"
    "insufficient_quality" -> "측정 불가"
    "needs_review" -> "재측정 필요"
    else -> "미측정"
}

@Composable
fun RangeReportCards(report: RangeReport, onExtend: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RangeResultCards(report)
        AdditionalVoiceReportCards(report)
        if (onExtend != null && report.availability == "available" && report.lowMidi != null && report.protocolVersion == com.micon.app.range.RangeProtocol.VERSION) OutlinePurpleButton("빈 음·경계 더 확인하기", onExtend, Modifier.fillMaxWidth())
    }
}

/** The same measured range is shown before and after timbre analysis. */
@Composable
fun RangeResultCards(report: RangeReport) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MiconCard {
            Text("이번에 확인한 음역", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            report.sampling?.let { s ->
                Text((if (s.basicCompleted) "기본 탐색 완료" else "부분 측정") + " · " + when (s.refinementStatus) {
                    "completed" -> "추가 확인 완료"
                    "in_progress" -> "추가 확인 일부 진행"
                    else -> "기본 결과"
                } + " · 측정한 음 " + s.measuredNoteCount + "개", fontSize = 12.sp, color = Grey)
            }
            Text(if (report.lowMidi != null && report.highMidi != null)
                "${ScoringMath.midiName(report.lowMidi)} ~ ${ScoringMath.midiName(report.highMidi)}" else "확정된 안정 음역이 없어요",
                fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Purple)
        }
        MiconCard {
            Text("음역대 지도", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val relative = report.protocolVersion == "guided-range-v4" || com.micon.app.range.RangeProtocol.buffered(report.protocolVersion)
            val rangeMap = report.continuousRangeMap()
            val rows = if (relative) listOfNotNull(
                rangeMap.overall?.let { RangeRow("전체 음역", it[0], it[1], PurpleMid) },
                rangeMap.stable?.let { RangeRow("안정 음역", it[0], it[1], GreenBar) },
                rangeMap.recommended?.let { RangeRow("추천 음역", it[0], it[1], Purple) })
                else listOf("통과 음" to setOf("passed", "reconfirmed"), "반복 확인" to setOf("reconfirmed"),
                "미통과·불안정" to setOf("not_passed", "inconsistent")).flatMapIndexed { index, (label, statuses) ->
                val confirmed = report.notes.filter { it.status in statuses }.map { it.midi }
                val mapped = if (index == 0) report.stableIntervals.filter { it.size == 2 && it[0] <= it[1] }
                    .flatMap { (it[0].coerceAtLeast(36)..it[1].coerceAtMost(84)).toList() }.filter { it in confirmed } else confirmed
                noteIntervals(mapped).map {
                    RangeRow(label, it.first, it.last, listOf(PurpleMid, GreenBar, Red)[index])
                }
            }
            Spacer(Modifier.height(10.dp))
            if (rows.isEmpty()) Text("아직 확인한 구간이 없어요", color = Grey)
            else GuidedRangeMapChart(rows, axisLow = 36, axisHigh = 84, ticks = listOf(36, 48, 60, 72, 84), labelWidth = 108.dp, noteCells = true)
        }
        RangeStabilityCard(report)
    }
}

@Composable
fun AdditionalVoiceReportCards(report: RangeReport, songFit: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MiconCard {
            Text("내 목소리에 맞는 음악", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("이번에 확인한 음", color = Grey, fontSize = 13.sp)
            GuidedRangeKeyboard(report.notes)
            Spacer(Modifier.height(18.dp))
            Text("적정 템포 / 가사 밀도 · 미측정", color = Grey, fontSize = 13.sp)
            BpmGaugeRow("—", Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Text("프레이즈 길이 · 미측정", color = Grey, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            PhraseSegments(0, modifier = Modifier.semantics { contentDescription = "프레이즈 길이 미측정, 7칸 비어 있음" })
            Spacer(Modifier.height(18.dp))
            Text("추천 장르 / 분위기 · 미측정", color = Grey, fontSize = 13.sp)
        }
        if (songFit != null) songFit() else MiconCard {
            Text("곡 적합도", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("프로필을 저장한 뒤 곡 적합도를 확인할 수 있어요.", color = Grey, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().background(GreyLight.copy(alpha = .35f), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(LavenderDeep, RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("분석할 노래", color = Grey, fontSize = 11.sp)
                    Text("아직 연결된 곡이 없어요", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuidedRingProgress(null, caption = "미측정")
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("음역", "음색", "박자").forEach { GuidedMetricBar(it, null, trailing = "—") }
                }
            }
            Text("추천 key · 미측정", color = Grey, fontSize = 13.sp)
        }
        MiconCard {
            Text("종합 보컬 특성", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuidedRadarHexagon(null, listOf("음역", "음정", "고음", "호흡", "리듬", "발음"), Modifier.weight(1f).height(190.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf("보컬 특성 · 미측정", "호흡 특성 · 미측정").forEach {
                        Text(it, Modifier.background(Lavender, RoundedCornerShape(12.dp)).padding(12.dp), color = Purple, fontSize = 12.sp)
                    }
                }
            }
            Text("미측정", color = Grey, fontSize = 12.sp)
        }
    }
}

private fun noteIntervals(notes: List<Int>): List<IntRange> {
    val intervals = mutableListOf<IntRange>()
    notes.distinct().sorted().forEach { midi ->
        if (intervals.lastOrNull()?.last == midi - 1) intervals[intervals.lastIndex] = intervals.last().first..midi
        else intervals.add(midi..midi)
    }
    return intervals
}

@Composable
fun RangeStabilityCard(report: RangeReport) {
    var selected by remember(report) { mutableStateOf(report.lowMidi ?: report.notes.firstOrNull { it.status != "unmeasured" }?.midi ?: 60) }
    var showAll by remember(report) { mutableStateOf(false) }
    var showMap by remember(report) { mutableStateOf(false) }
    val relative = report.protocolVersion in setOf("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")
    val plot = remember(report, showAll) { RangeStabilityPlot.from(report.notes, showAll, relative) }
    LaunchedEffect(plot) {
        if (plot.points.none { it.note.midi == selected }) {
            selected = plot.points.firstOrNull { it.ratio != null }?.note?.midi ?: plot.points.first().note.midi
        }
    }
    MiconCard {
        Text("음역별 안정성", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        RangeStabilityChart(plot, selected, { selected = it })
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("● 통과·반복 확인", color = Purple, fontSize = 12.sp)
            Text("● 미통과·재현 불안정", color = Red, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        plot.points.firstOrNull { it.note.midi == selected }?.note?.let { note ->
            Column(Modifier.fillMaxWidth().background(Lavender.copy(alpha = .65f), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RangeNoteDetails(note)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) "음별 지도 접기" else "음별 지도 펼치기", fontSize = 12.sp) }
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "측정 주변만 보기" else "전체 C2~C6 보기", fontSize = 12.sp) }
        }
        if (showMap) {
            Text("✓ 통과  ·  ◎ 반복 확인  ·  △ 미통과/재측정  ·  – 미측정", fontSize = 11.sp, color = Grey)
            plot.points.map { it.note }.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { note ->
                        val label = noteStatus(note.status)
                        val color = when (note.status) {
                            "passed", "reconfirmed" -> GreenChipText
                            "unmeasured" -> Grey
                            else -> Red
                        }
                        val symbol = when (note.status) { "passed" -> "✓"; "reconfirmed" -> "◎"; "unmeasured" -> "–"; else -> "△" }
                        Column(Modifier.weight(1f).background(if (selected == note.midi) Lavender else GreyLight.copy(alpha = .25f), RoundedCornerShape(8.dp))
                            .clickable { selected = note.midi }.semantics { contentDescription = "${ScoringMath.midiName(note.midi)} $label" }
                            .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(ScoringMath.midiName(note.midi), fontSize = 13.sp)
                            Text(symbol, color = color, fontSize = 18.sp)
                        }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RangeNoteDetails(note: RangeNote) {
    Text("${ScoringMath.midiName(note.midi)} · ${noteStatus(note.status)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    if (note.status in setOf("unmeasured", "insufficient_quality", "needs_review")) {
        Text(when (note.status) {
            "unmeasured" -> "미측정"
            "needs_review" -> "재측정 필요"
            else -> "측정 불가"
        }, color = Grey, fontSize = 13.sp)
    } else {
        note.metrics?.let { m ->
            if (m.stabilityScore != null) {
                Text("안정성 ${String.format(Locale.ROOT, "%.1f", m.stabilityScore)}점 / 100점", color = InkSoft)
                LinearProgressIndicator(progress = { (m.stabilityScore / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (m.creditedHoldSeconds != null) {
                    Text("인정 유지 ${String.format(Locale.ROOT, "%.2f", m.creditedHoldSeconds)}초 · 엄격한 연속 유지 ${String.format(Locale.ROOT, "%.2f", m.continuousHoldSeconds)}초", fontSize = 12.sp)
                    Text("완충 ${String.format(Locale.ROOT, "%.2f", m.bufferSeconds)}초 · 범위 밖 ${String.format(Locale.ROOT, "%.2f", m.outsideSeconds)}초 · 감점 ${String.format(Locale.ROOT, "%.1f", m.excursionPenalty)}점", fontSize = 12.sp)
                } else Text("최장 연속 유지 ${String.format(Locale.ROOT, "%.2f", m.continuousHoldSeconds)}초", fontSize = 12.sp)
                Text("${if (m.referenceRmsCents != null) "시작 높이 대비 흔들림" else "흔들림"} ${String.format(Locale.ROOT, "%.1f", m.referenceRmsCents ?: m.pitchSpreadCents)} cents", fontSize = 12.sp)
                Text("실제 발성 ${m.actualMidi?.let(ScoringMath::midiName) ?: "—"} · 기록 음 ${ScoringMath.midiName(note.midi)}", fontSize = 12.sp)
            } else {
            Text("목표음 유지율 ${String.format(Locale.ROOT, "%.0f", m.inTuneRatio * 100)}%", color = InkSoft)
            LinearProgressIndicator(progress = { m.inTuneRatio.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("음정 오차 ${m.medianAbsErrorCents?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"} cents · 흔들림 ${m.pitchSpreadCents?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"} cents", fontSize = 12.sp)
            Text("연결 유지 ${String.format(Locale.ROOT, "%.2f", m.longestHoldSeconds)}초", fontSize = 12.sp)
            Text("유효 발성 ${String.format(Locale.ROOT, "%.0f", m.voicedRatio * 100)}%", fontSize = 12.sp)
            }
        } ?: Text("측정값 없음", color = Grey, fontSize = 13.sp)
    }
    val register = when (note.registerEstimate) { "modal" -> "진성 우세 추정"; "falsetto" -> "가성 우세 추정"; "mixed" -> "혼합 추정"; else -> "성구 불명확" }
    if (note.status != "unmeasured") Text(register + if (note.selfReportedRegister == "falsetto") " · 사용자 선택: 가성" else "", fontSize = 12.sp, color = Grey)
}
