package com.micon.app.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import com.micon.app.ui.charts.GuidedPitchGauge
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.charts.measurementKeyboardMode
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.scoring.ScoringMath
import com.micon.app.range.RangeNote
import com.micon.app.range.RangeSession
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.OutlinePurpleButton
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
fun RangePitchVisuals(session: RangeSession.View, gaugeHeight: Dp = 240.dp, ringSize: Dp = 64.dp,
    soundInput: @Composable () -> Unit = {},
) {
    session.targetMidi?.let { midi ->
        val feedback = rangeHoldFeedback(session)
        Column(Modifier.testTag("range-pitch-and-hold"), horizontalAlignment = Alignment.CenterHorizontally) {
            GuidedPitchGauge(ScoringMath.midiName(midi), (midi-2..midi+2).map(ScoringMath::midiName),
                session.currentMidi?.let { ((it-midi)*100).toFloat() }, session.pitchZone == "normal",
                modifier = Modifier.testTag("range-pitch-gauge"),
                holdSeconds = session.holdProgress * 2.5f, gaugeHeight = gaugeHeight, ringSize = ringSize,
                needleColor = when(session.pitchZone) { "normal" -> Teal; "buffer" -> Orange; "outside" -> Red; else -> Grey },
                holdLabel = "인정 유지", compact = true)
            soundInput()
            Text(feedback.orEmpty(), color = if (feedback == "통과") GreenChipText else Purple,
                fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, minLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("range-hold-feedback"))
        }
    }
}

fun rangeHoldFeedback(session: RangeSession.View): String? = when {
    session.targetMidi == null || session.inputSuppressed || session.waitingForCue -> null
    session.metrics?.passed == true || session.bestMetrics?.passed == true -> "통과"
    session.referenceMidi != null -> "현재 음을 유지해주세요"
    else -> null
}

@Composable
fun RangeMeasurementPanel(session: RangeSession.View, level: Float, notes: List<RangeNote>,
                          pausedForChoice: Boolean, onReplay: () -> Unit, modifier: Modifier = Modifier,
                          inputReady: Boolean = false, inputMessage: String? = null) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val shortScreen = constraints.hasBoundedHeight && maxHeight < 450.dp
        val keyboardHeight = if (shortScreen) 112.dp else 128.dp
        val ringSize = if (shortScreen) 56.dp else 64.dp
        val density = LocalDensity.current
        val feedbackHeight = with(density) { 18.sp.toDp() }
        val replayHeight = with(density) { (24.sp.toDp() + 16.dp).coerceAtLeast(48.dp) }
        val reservedHeight = ringSize + keyboardHeight + feedbackHeight + 32.dp + replayHeight + 18.dp
        val gaugeHeight = if (constraints.hasBoundedHeight) (maxHeight - reservedHeight).coerceIn(80.dp, 240.dp) else 240.dp
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val soundInput: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().testTag("range-sound-input").padding(vertical = 4.dp)) { WaveformBars(level, height = 24.dp) }
            }
            if (session.targetMidi == null) soundInput()
            else RangePitchVisuals(session, gaugeHeight, ringSize, soundInput)
            GuidedRangeKeyboard(notes, session.targetMidi, session.currentMidi, provisional = true,
                mode = measurementKeyboardMode(session.phase), showTargetLabel = false, showPassedLegend = false,
                keyboardHeight = keyboardHeight)
            session.targetMidi?.let { midi ->
                OutlinedButton(onClick = onReplay, enabled = !session.waitingForCue,
                    modifier = Modifier.fillMaxWidth().height(replayHeight)) {
                    Text(if (session.waitingForCue) "${ScoringMath.midiName(midi)} 재생 중…" else "${ScoringMath.midiName(midi)} 듣기")
                }
            }
            if (session.phase == "profile_ready") RangeProfileChoiceMessage(session.sampling.measuredNoteCount, pausedForChoice, inputReady, inputMessage)
            else if (session.phase in setOf("noise", "comfortable")) Text(session.message, color = Grey)
        }
    }
}

@Composable
fun RangeAdvanceButton(session: RangeSession.View, onNext: (String?) -> Unit, modifier: Modifier = Modifier,
    height: Dp = 56.dp,
) {
    if (session.targetMidi != null) PrimaryButton(if (session.canResetAnchor) "시작음 다시 찾기" else "이 음 패스하기",
        { onNext(session.visitId) }, modifier, height = height)
}

@Composable
fun RangePassGuide(session: RangeSession.View?) {
    if (session?.targetMidi != null && !session.canResetAnchor) {
        Text("소리 내기 어려운 음은 패스할 수 있어요.", color = Grey, fontSize = 13.sp,
            modifier = Modifier.testTag("range-pass-guide"), lineHeight = 17.sp)
    }
}

fun rangeMeasurementCanFinish(session: RangeSession.View?, inputReady: Boolean = false): Boolean =
    inputReady && session?.sampling?.let { it.basicCompleted || it.refinementStatus == "completed" } == true

@Composable
fun RangeMeasurementActions(session: RangeSession.View?, onFinish: () -> Unit, onNext: (String?) -> Unit,
    inputReady: Boolean = false) {
    val density = LocalDensity.current
    val actionHeight = with(density) { (24.sp.toDp() + 24.dp).coerceAtLeast(48.dp) }
    val canFinish = rangeMeasurementCanFinish(session, inputReady)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (session != null) RangeAdvanceButton(session, onNext, Modifier.fillMaxWidth(), height = actionHeight)
        if (canFinish && session?.targetMidi != null) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp).testTag("range-action-divider"), color = GreyLight)
        }
        if (canFinish) OutlinePurpleButton("여기까지 측정하기", onFinish, Modifier.fillMaxWidth(), height = actionHeight)
    }
}
