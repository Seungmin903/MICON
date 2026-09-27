package com.micon.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeNote
import com.micon.app.range.RangeStabilityPlot
import com.micon.app.range.LocalVoiceQualityAnalyzer
import com.micon.app.range.VoiceQualityPoint
import com.micon.app.range.continuousRangeMap
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
import com.micon.app.ui.common.RangeNoteNames
import com.micon.app.ui.common.ReportNoteNames
import com.micon.app.ui.common.sharpNoteText
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
    }
}

/** The same measured range is shown before and after timbre analysis. */
@Composable
fun RangeResultCards(report: RangeReport, reportTab: Boolean = false) {
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
            val rangeLabel = if (report.lowMidi != null && report.highMidi != null)
                (if (reportTab) ReportNoteNames.range(report.lowMidi, report.highMidi)
                    else RangeNoteNames.range(report.lowMidi, report.highMidi)) else "확정된 안정 음역이 없어요"
            Text(sharpNoteText(rangeLabel, 24.sp),
                modifier = Modifier.fillMaxWidth(), fontSize = 24.sp, lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold, color = Purple,
                style = TextStyle.Default.copy(lineBreak = LineBreak.Heading))
        }
        MiconCard(Modifier.testTag("range-map-card")) {
            Text("음역대 지도", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val relative = report.protocolVersion == "guided-range-v4" || com.micon.app.range.RangeProtocol.buffered(report.protocolVersion) ||
                report.protocolVersion == com.micon.app.range.RangeProtocol.FREE_VERSION
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
            else GuidedRangeMapChart(rows, axisLow = 36, axisHigh = 84,
                ticks = if (LocalDensity.current.fontScale > 1.3f) listOf(36, 84) else listOf(36, 60, 84),
                labelWidth = 108.dp, noteCells = true,
                labelOf = if (reportTab) ReportNoteNames::note else RangeNoteNames::short)
            if (reportTab) {
                Spacer(Modifier.height(6.dp))
                GuidedRangeKeyboard(report.notes, noteName = ReportNoteNames::note, showPassedLegend = false)
            }
        }
        if (report.voiceQuality?.points?.isNotEmpty() == true) RangeStabilityCard(report, overview = reportTab)
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
fun RangeStabilityCard(report: RangeReport, overview: Boolean = false) {
    val quality = report.voiceQuality?.takeIf { it.version == LocalVoiceQualityAnalyzer.VERSION && it.points.isNotEmpty() }
    val plottedNotes = quality?.graphNotes() ?: report.notes
    var selected by remember(report) { mutableStateOf(quality?.referenceMidi ?: report.lowMidi ?: report.notes.firstOrNull { it.status != "unmeasured" }?.midi ?: 60) }
    val relative = quality != null || report.protocolVersion in setOf("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")
    val plot = remember(report) { RangeStabilityPlot.from(plottedNotes, scoreBased = relative) }
    LaunchedEffect(plot) {
        if (plot.points.none { it.note.midi == selected }) {
            selected = plot.points.firstOrNull { it.ratio != null }?.note?.midi ?: plot.points.first().note.midi
        }
    }
    MiconCard {
        Text(if (quality != null) "음역별 발성 유지 점수" else "음역별 안정성",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        RangeStabilityChart(plot, selected, { selected = it }, overview = overview,
            scoreLabel = if (quality != null) "개인 기준 발성 유지 점수" else null)
        if (quality != null) {
            Text("점은 실제 측정값이고, 빈 음을 지나는 점선은 보기 쉽게 이은 선이에요.",
                color = Grey, fontSize = 12.sp)
        } else if (LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { RangeStabilityLegend() }
        } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RangeStabilityLegend()
        }
        if (!overview) plot.points.firstOrNull { it.note.midi == selected }?.note?.let { note ->
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(Lavender.copy(alpha = .65f), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (quality != null) VoiceQualityDetails(quality.points.firstOrNull { it.midi == selected })
                else RangeNoteDetails(note)
            }
        }
    }
}

@Composable
private fun VoiceQualityDetails(point: VoiceQualityPoint?) {
    if (point == null) {
        Text("이 음은 음높이를 확인할 만한 목소리 기록이 없어 점수를 표시하지 않아요.", color = Grey)
        return
    }
    Text(sharpNoteText(RangeNoteNames.full(point.midi), 18.sp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text("개인 기준 발성 유지 ${String.format(Locale.ROOT, "%.0f", point.score)}점 / 100점", color = InkSoft)
    if (point.score == 0.0 && point.holdScore == 0.0)
        Text("음높이는 감지했지만 짧게 끊겨 0점으로 기록했어요.", color = Grey, fontSize = 12.sp)
    LinearProgressIndicator(progress = { (point.score / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth())
    Text("음 유지 ${String.format(Locale.ROOT, "%.0f", point.holdScore)}점 · " +
        "고음 변화 지표 ${String.format(Locale.ROOT, "%.2f", point.highChange)} · " +
        "저음 불규칙 지표 ${String.format(Locale.ROOT, "%.2f", point.lowIrregularity)} (각 0~1)",
        color = Grey, fontSize = 12.sp)
}

@Composable
private fun RangeStabilityLegend() {
    Text("● 통과·반복 확인", color = Purple, fontSize = 12.sp)
    Text("● 미통과·재현 불안정", color = Red, fontSize = 12.sp)
}

@Composable
private fun RangeNoteDetails(note: RangeNote) {
    Text(sharpNoteText("${RangeNoteNames.full(note.midi)} · ${noteStatus(note.status)}", 18.sp),
        fontSize = 18.sp, fontWeight = FontWeight.Bold,
        style = TextStyle.Default.copy(lineBreak = LineBreak.Heading))
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
                Text("실제 발성 ${m.actualMidi?.let(RangeNoteNames::full) ?: "—"} · 기록 음 ${RangeNoteNames.full(note.midi)}", fontSize = 12.sp)
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
