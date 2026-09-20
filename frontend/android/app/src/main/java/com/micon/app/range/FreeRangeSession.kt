package com.micon.app.range

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class FreeRangeCheckpoint(val id: String, val stages: List<FreeRangeStage>,
    val noiseRms: Double, val audioSeconds: Double)

/** Target-free, audio-clock-driven onboarding and stand-alone range measurement. */
class FreeRangeSession(val id: String = UUID.randomUUID().toString()) {
    data class View(
        val phase: String,
        val completed: Int,
        val currentMidi: Double?,
        val holdSeconds: Double,
        val canFinish: Boolean,
        val elapsed: Double,
        val message: String,
    )

    private var phase = "noise"
    private var failedPhase = "comfort"
    private var nextPhase = "comfort"
    private var now = 0.0
    private var stageStart = 0.0
    private var noiseRms = 0.0
    private var floor = .001
    private val noiseFrames = mutableListOf<RangeFrame>()
    private val stages = mutableListOf<FreeRangeStage>()
    private var currentMidi: Double? = null
    private var startMidi: Double? = null
    private var extreme: Double? = null
    private var holdMidi: Double? = null
    private var holdReferenceMidi: Double? = null
    private val holdMidis = mutableListOf<Double>()
    private var holdStart: Double? = null
    private var holdEnd: Double? = null
    private var lastValidEnd: Double? = null
    private var voicedSeconds = 0.0
    private var silenceStart: Double? = null
    private var canFinish = false
    private var message = "잠시 조용히 계세요"

    @Synchronized fun view() = View(phase, stages.size, currentMidi,
        if (holdStart != null && holdEnd != null) (holdEnd!! - holdStart!!).coerceAtLeast(0.0) else 0.0,
        canFinish, now - stageStart, message)

    @Synchronized fun onFrame(frame: RangeFrame): View {
        if (!frame.t.isFinite() || !frame.duration.isFinite() || frame.duration <= 0 || frame.t + frame.duration < now - .1) return view()
        now = max(now, frame.t + frame.duration)
        if (phase == "noise") {
            noiseFrames.add(frame)
            noiseFrames.removeAll { it.t + it.duration < now - 1.0 }
            if (noiseFrames.sumOf { it.duration } >= 1.0 && noiseFrames.none { RangeEvaluator.voiced(it, .001) }) {
                noiseRms = RangeEvaluator.median(noiseFrames.map { it.rms }) ?: 0.0
                floor = max(.001, noiseRms * 3.16)
                noiseFrames.clear()
                begin("comfort", now)
            } else if (now >= 20.0) fail()
            return view()
        }
        if (phase !in setOf("comfort", "down", "up")) return view()
        val valid = RangeEvaluator.voiced(frame, floor) && frame.clippedRatio <= .01 &&
            frame.f0.isFinite() && RangeProtocol.midi(frame.f0) in RangeProtocol.MIN_MIDI.toDouble()..RangeProtocol.MAX_MIDI.toDouble()
        if (!valid) {
            currentMidi = null
            if (frame.rmsLow < floor && silenceStart == null) silenceStart = frame.t
            if (frame.rmsLow >= floor) silenceStart = null
            if (canFinish && phase != "comfort" && silenceStart != null && now - silenceStart!! >= 5.0) complete(now)
            else if (!canFinish && now - stageStart >= 20.0) fail()
            else if (now - stageStart >= 25.0) fail()
            return view()
        }
        silenceStart = null
        if (now - stageStart >= 20.0 && !canFinish) { fail(); return view() }
        val midi = RangeProtocol.midi(frame.f0)
        currentMidi = midi
        if (phase in setOf("down", "up") && startMidi == null &&
            abs(midi - stages.first().endpointMidi) > 1.5) {
            message = "먼저 편안한 음에서 시작해 주세요"
            return view()
        }
        val frameEnd = frame.t + frame.duration
        if (lastValidEnd != null && frame.t - lastValidEnd!! > .12) resetHold()
        lastValidEnd = frameEnd
        voicedSeconds += frame.duration.coerceAtMost(.04)
        if (startMidi == null) startMidi = midi
        if (phase == "comfort") {
            if (holdReferenceMidi == null || abs(midi - holdReferenceMidi!!) > HOLD_TOLERANCE_SEMITONES) startHold(midi, frame.t, frameEnd)
            else extendHold(midi, frameEnd)
            if (holdDuration() >= HOLD_SECONDS) complete(now)
            return view()
        }
        val direction = if (phase == "down") -1 else 1
        if (extreme == null || direction * (midi - extreme!!) > .45) {
            extreme = midi; startHold(midi, frame.t, frameEnd); canFinish = false
        } else if (abs(midi - (holdReferenceMidi ?: extreme!!)) <= HOLD_TOLERANCE_SEMITONES) {
            extendHold(midi, frameEnd)
        } else resetHold()
        canFinish = direction * ((holdMidi ?: midi) - startMidi!!) >= 2.0 &&
            voicedSeconds >= 2.0 && (holdStart ?: now) - stageStart >= 1.0 && holdDuration() >= HOLD_SECONDS
        message = when {
            canFinish -> "여기까지 누르거나 소리를 멈추면 다음 단계로 넘어가요"
            direction * ((extreme ?: midi) - startMidi!!) < 2.0 -> "편안한 음에서 천천히 ${if (direction < 0) "내려" else "올려"} 주세요"
            else -> "마지막 음을 0.5초 유지해 주세요"
        }
        return view()
    }

    @Synchronized fun finishCurrent(audioTime: Double): Boolean {
        if (!canFinish || phase !in setOf("down", "up")) return false
        complete(max(now, audioTime))
        return true
    }

    @Synchronized fun advance(audioTime: Double): Boolean {
        if (phase != "transition") return false
        begin(nextPhase, max(now, audioTime))
        return true
    }

    @Synchronized fun retryCurrent(audioTime: Double): Boolean {
        if (phase !in setOf("failed", "noise", "comfort", "down", "up")) return false
        begin(if (phase == "failed") failedPhase else phase, audioTime)
        return true
    }

    @Synchronized fun submission(duration: Double): RangeSubmission? {
        if (phase != "done" || stages.map { it.kind } != listOf("comfort", "down", "up")) return null
        return RangeSubmission(sessionId = id, protocolVersion = RangeProtocol.FREE_VERSION,
            startMidi = stages.first().endpointMidi.roundToInt().coerceIn(36, 84),
            noiseRms = noiseRms, durationSeconds = duration, stopReason = "completed",
            lowerBoundaryStatus = "endpoint_held", upperBoundaryStatus = "endpoint_held",
            attempts = emptyList(), registerEvents = emptyList(), stages = stages.toList())
    }

    @Synchronized fun checkpoint(audioSeconds: Double): FreeRangeCheckpoint? =
        if (phase in setOf("transition", "done") && stages.isNotEmpty())
            FreeRangeCheckpoint(id, stages.toList(), noiseRms, audioSeconds) else null

    companion object {
        const val HOLD_SECONDS = 0.5
        const val HOLD_TOLERANCE_SEMITONES = 1.5

        fun restore(checkpoint: FreeRangeCheckpoint): FreeRangeSession? {
            if (checkpoint.stages.map { it.kind } != listOf("comfort", "down", "up").take(checkpoint.stages.size)
                || checkpoint.stages.size !in 1..3 || checkpoint.noiseRms !in 0.0..1.0
                || !checkpoint.audioSeconds.isFinite() || checkpoint.audioSeconds < checkpoint.stages.last().end)
                return null
            return FreeRangeSession(checkpoint.id).apply {
                stages.addAll(checkpoint.stages)
                noiseRms = checkpoint.noiseRms
                floor = max(.001, noiseRms * 3.16)
                now = checkpoint.audioSeconds
                phase = if (stages.size == 3) "done" else "transition"
                nextPhase = if (stages.size == 1) "down" else "up"
                message = if (phase == "done") "음역 측정을 마쳤어요" else "${stages.size}/3 완료 · 다음 단계를 시작해 주세요"
            }
        }
    }

    private fun begin(name: String, time: Double) {
        phase = name; stageStart = time; now = max(now, time)
        if (name == "noise") noiseFrames.clear()
        startMidi = null; extreme = null; holdMidi = null; holdReferenceMidi = null; holdMidis.clear(); holdStart = null; holdEnd = null
        lastValidEnd = null; voicedSeconds = 0.0; silenceStart = null; canFinish = false
        currentMidi = null
        message = when (name) {
            "noise" -> "잠시 조용히 계세요"
            "comfort" -> "편한 목소리로 '아'를 0.5초 내주세요"
            "down" -> "편안한 음에서 천천히 목소리를 내려주세요"
            else -> "편안한 음에서 천천히 목소리를 높여주세요"
        }
    }

    private fun startHold(midi: Double, start: Double, end: Double) {
        holdMidis.clear()
        holdMidis += midi
        holdReferenceMidi = midi
        holdMidi = midi
        holdStart = start
        holdEnd = end
    }
    private fun extendHold(midi: Double, end: Double) {
        holdMidis += midi
        holdMidi = RangeEvaluator.median(holdMidis)
        holdEnd = end
    }
    private fun resetHold() { holdMidi = null; holdReferenceMidi = null; holdMidis.clear(); holdStart = null; holdEnd = null; canFinish = false }
    private fun fail() {
        failedPhase = phase
        phase = "failed"
        canFinish = false
        message = "목소리를 확인하지 못했어요. 이 단계만 다시 시도해 주세요"
    }
    private fun holdDuration() = if (holdStart != null && holdEnd != null) holdEnd!! - holdStart!! else 0.0

    private fun complete(time: Double) {
        val origin = startMidi ?: return
        val endpoint = holdMidi ?: return
        val heldFrom = holdStart ?: return
        val heldUntil = holdEnd ?: return
        stages.add(FreeRangeStage(phase, stageStart, time, origin, endpoint, heldFrom, heldUntil))
        nextPhase = if (phase == "comfort") "down" else "up"
        phase = if (stages.size == 3) "done" else "transition"
        canFinish = false
        message = if (phase == "done") "음역 측정을 마쳤어요" else "${stages.size}/3 완료 · 다음 단계를 시작해 주세요"
    }
}
