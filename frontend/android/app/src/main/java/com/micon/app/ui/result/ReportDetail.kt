package com.micon.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.PracticeAssets
import com.micon.app.data.backend.dto.toLiveNotes
import com.micon.app.data.practice.PitchCurve
import com.micon.app.data.practice.PracticeReport
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 절충안 리포트 상세: 서버가 돌려준 노트별 판정([PracticeReport.notes])과 음정 곡선([PracticeReport.curve])을
 * practice-assets 의 목표 음표·가사와 결합한 화면 모델. 서버 AI 없이 기기 채점 결과만으로 그린다.
 */
data class ReportDetail(
    /** 판정된 음표가 하나라도 있는 가사 줄만 */
    val lines: List<JudgedLine>,
    /** 목표 음정(키 이동 적용) 구간 — 곡선 그래프의 기준선 */
    val targets: List<TargetSegment>,
    val userCurve: PitchCurve?,
    val keyShift: Int,
    /** 저장 당시 dataVersion 과 지금 받은 practice-assets 의 dataVersion 이 다르면 true(노트 인덱스가 어긋날 수 있음) */
    val versionMismatch: Boolean,
) {
    val counts: Map<LiveStatus, Int> = lines.flatMap { it.chars }.mapNotNull { it.status }.groupingBy { it }.eachCount()
}

data class JudgedChar(val text: String, val status: LiveStatus?)
data class JudgedLine(val startSec: Double, val chars: List<JudgedChar>)
data class TargetSegment(val start: Double, val end: Double, val midi: Double)

object ReportDetailBuilder {
    /**
     * [report] 의 notes 를 [assets] 의 목표 음표(`toLiveNotes` 정렬 순서)에 인덱스로 붙이고 가사 줄로 묶는다.
     * 인덱스가 범위를 벗어나면 그 판정은 버린다(버전 불일치 대비).
     */
    fun build(assets: PracticeAssets, report: PracticeReport): ReportDetail {
        val live = assets.targetNotes.toLiveNotes(assets.timeSignature)
        val byIndex = report.notes.associateBy { it.index }
        for (n in live) {
            val j = byIndex[n.index] ?: continue
            n.status = runCatching { LiveStatus.valueOf(j.status) }.getOrDefault(LiveStatus.UPCOMING)
            n.cents = j.cents
            n.deviation = j.deviation
        }
        val sheet = LyricLines.build(live, assets.lyricsSynced, assets.lyricsPlain)
        val lines = sheet.lines
            .filter { line -> line.notes.any { it.status.judged } }
            .map { line -> JudgedLine(line.start, line.chars.map { c -> JudgedChar(c.text, c.note?.status?.takeIf { s -> s.judged || s == LiveStatus.SKIPPED }) }) }
        val targets = live.map { TargetSegment(it.start, it.end, (it.midi + report.selectedKey).toDouble()) }
        val mismatch = report.dataVersion != null && assets.dataVersion != null && report.dataVersion != assets.dataVersion
        return ReportDetail(lines, targets, report.curve?.takeIf { !it.isEmpty }, report.selectedKey, mismatch)
    }
}

/** 가사 음절별 판정 카드 — Figma "구간 평가"의 음이탈 음절 빨강 표시. */
@Composable
fun SyllableJudgementCard(detail: ReportDetail) {
    MiconCard {
        CardTitle("가사별 음정 판정")
        if (detail.versionMismatch) {
            Text("곡 데이터가 갱신되어 글자 위치가 조금 어긋날 수 있어요.", fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Legend(judgementColor(LiveStatus.GOOD), "Perfect")
            Legend(judgementColor(LiveStatus.SHARP), "Low")
            Legend(judgementColor(LiveStatus.FLAT), "High")
            Legend(judgementColor(LiveStatus.WRONG), "Bad")
            Legend(judgementColor(LiveStatus.MISSED), "Miss")
        }
        Spacer(Modifier.height(10.dp))
        if (detail.lines.isEmpty()) {
            Text("판정된 가사 줄이 없어요.", fontSize = 14.sp, color = Grey)
        } else {
            Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.lines.forEach { line -> JudgedLineRow(line) }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Grey)
    }
}

@Composable
private fun JudgedLineRow(line: JudgedLine) {
    Row(verticalAlignment = Alignment.Top) {
        Text(PracticeLabels.duration((line.startSec * 1000).roundToInt()) ?: "0:00", fontSize = 12.sp, color = Grey, modifier = Modifier.width(40.dp).padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                line.chars.forEach { c ->
                    when (c.status) {
                        LiveStatus.SHARP -> withStyle(SpanStyle(color = judgementColor(c.status), fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(c.text) }
                        LiveStatus.FLAT -> withStyle(SpanStyle(color = judgementColor(c.status), fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(c.text) }
                        LiveStatus.WRONG -> withStyle(SpanStyle(color = judgementColor(c.status), fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(c.text) }
                        LiveStatus.MISSED -> withStyle(SpanStyle(color = judgementColor(c.status))) { append(c.text) }
                        LiveStatus.GOOD -> withStyle(SpanStyle(color = judgementColor(c.status))) { append(c.text) }
                        else -> withStyle(SpanStyle(color = Ink.copy(alpha = 0.7f))) { append(c.text) }
                    }
                }
            },
            fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f),
        )
    }
}

/** 목표 음정(보라) vs 내 음정(빨강) 곡선 카드. 세로축은 MIDI, 가로축은 곡 시각. */
@Composable
fun PitchCurveCard(detail: ReportDetail) {
    val curve = detail.userCurve
    val targets = detail.targets
    MiconCard {
        CardTitle("음정 곡선")
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Legend(
                Purple,
                "목표 음정" + if (detail.keyShift != 0) " (키 ${if (detail.keyShift > 0) "+" else ""}${detail.keyShift})" else "",
            )
            Legend(Red, "내 음정")
        }
        Spacer(Modifier.height(10.dp))
        if (curve == null || targets.isEmpty()) {
            Text("저장된 음정 곡선이 없어요.", fontSize = 14.sp, color = Grey)
            return@MiconCard
        }
        val userPoints = curve.user.mapIndexedNotNull { i, m -> m?.let { Offset(((curve.startMs + i * curve.hopMs) / 1000.0).toFloat(), it.toFloat()) } }
        val tMin = minOf(targets.first().start, userPoints.firstOrNull()?.x?.toDouble() ?: targets.first().start)
        val tMax = maxOf(targets.last().end, userPoints.lastOrNull()?.x?.toDouble() ?: targets.last().end)
        val midiValues = targets.map { it.midi } + userPoints.map { it.y.toDouble() }
        val yLow = floor(midiValues.min() - 1).toInt()
        val yHigh = ceil(midiValues.max() + 1).toInt()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.height(170.dp).width(34.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(ScoringMath.midiName(yHigh.toDouble()), fontSize = 11.sp, color = Grey)
                Text(ScoringMath.midiName(((yLow + yHigh) / 2).toDouble()), fontSize = 11.sp, color = Grey)
                Text(ScoringMath.midiName(yLow.toDouble()), fontSize = 11.sp, color = Grey)
            }
            Canvas(Modifier.weight(1f).height(170.dp).background(GreyBg, RoundedCornerShape(12.dp))) {
                val w = size.width; val h = size.height
                val span = (tMax - tMin).coerceAtLeast(1.0)
                fun x(t: Double) = (((t - tMin) / span) * w).toFloat()
                fun y(m: Double) = (h - ((m - yLow) / (yHigh - yLow).coerceAtLeast(1)) * h).toFloat()
                // 옥타브(C) 가이드
                var c = yLow - (yLow % 12 + 12) % 12
                while (c <= yHigh) {
                    if (c >= yLow) drawLine(GreyLight, Offset(0f, y(c.toDouble())), Offset(w, y(c.toDouble())), strokeWidth = 1f)
                    c += 12
                }
                // 목표 계단
                for (seg in targets) {
                    val yy = y(seg.midi)
                    drawLine(Purple, Offset(x(seg.start), yy), Offset(x(seg.end), yy), strokeWidth = 5f, cap = StrokeCap.Round)
                }
                // 사용자 곡선: 무성(null) 에서 끊기
                val path = Path(); var open = false
                var prevIndex = -2
                curve.user.forEachIndexed { i, m ->
                    if (m == null) { open = false; return@forEachIndexed }
                    val px = x((curve.startMs + i * curve.hopMs) / 1000.0); val py = y(m)
                    if (!open || i != prevIndex + 1) { path.moveTo(px, py); open = true } else path.lineTo(px, py)
                    prevIndex = i
                }
                drawPath(path, Red, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            }
        }
    }
}

/** 연습 중 판정 글자와 동일한 공통 PitchStatus 팔레트. */
private fun judgementColor(status: LiveStatus): Color = Color(status.toPitchStatus()!!.argb)
