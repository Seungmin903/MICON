package com.micon.app.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.micon.app.ui.charts.GuidedPitchGauge
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.scoring.ScoringMath
import com.micon.app.range.RangeNote
import com.micon.app.range.RangeSession
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.*

fun rangeStageLabel(session: RangeSession.View): String = when (session.phase) {
    "noise", "comfortable" -> "준비"
    "anchor" -> if (session.canResetAnchor) "시작음 확인" else "중앙 세 음 확인"
    "central" -> "중앙 세 음 확인"
    "profile_ready" -> "기본 탐색 완료"
    "down" -> when (session.probeStage) { "bisect" -> "저음 사이 음 확인"; "coarse" -> "${session.sampling.coarseStep}반음씩 저음 탐색"; else -> "저음 확인" }
    "up", "up_return" -> when (session.probeStage) { "bisect" -> "고음 사이 음 확인"; "coarse" -> "${session.sampling.coarseStep}반음씩 고음 탐색"; else -> "고음 확인" }
    "reconfirm" -> "경계 다시 확인"
    else -> "빈 음·경계 추가 확인"
}

@Composable
fun RangeInputVisuals(session: RangeSession.View, level: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        session.targetMidi?.let { midi ->
            Column(Modifier.testTag("range-pitch-and-hold")) {
                GuidedPitchGauge(ScoringMath.midiName(midi), (midi-2..midi+2).map(ScoringMath::midiName),
                    session.currentMidi?.let { ((it-midi)*100).toFloat() }, session.pitchZone == "normal",
                    session.holdProgress * 2.5f, gaugeHeight = 120.dp, ringSize = 64.dp,
                    needleColor = when(session.pitchZone) { "normal" -> Teal; "buffer" -> Orange; "outside" -> Red; else -> Grey },
                    holdLabel = "인정 유지")
            }
        }
        Column(Modifier.testTag("range-sound-input")) { WaveformBars(level) }
    }
}

@Composable
fun RangeMeasurementPanel(session: RangeSession.View, level: Float, notes: List<RangeNote>,
                          pausedForChoice: Boolean, onReplay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(rangeStageLabel(session), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${session.elapsed.toInt() / 60}분 ${session.elapsed.toInt() % 60}초 경과", color = Grey)
        }
        RangeInputVisuals(session, level)
        GuidedRangeKeyboard(notes, session.targetMidi, session.currentMidi, provisional = true)
        session.targetMidi?.let { midi ->
            OutlinedButton(onClick = onReplay, enabled = !session.waitingForCue, modifier = Modifier.fillMaxWidth()) {
                Text(if (session.waitingForCue) "기준음 ${ScoringMath.midiName(midi)} 재생 중…" else "기준음 ${ScoringMath.midiName(midi)} 듣기")
            }
        }
        if (session.phase == "profile_ready") RangeProfileChoiceMessage(session.sampling.measuredNoteCount, pausedForChoice)
        else if (session.phase in setOf("noise", "comfortable")) Text(session.message, color = Grey)
    }
}

@Composable
fun RangeAdvanceButton(session: RangeSession.View, onNext: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (session.targetMidi != null) PrimaryButton(if (session.canResetAnchor) "시작음 다시 찾기" else "넘어가기",
        { onNext(session.visitId) }, modifier)
}
