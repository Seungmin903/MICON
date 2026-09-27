package com.micon.app.range

import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
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
        val elapsed: Double,
        val message: String,
        val remainingSeconds: Double = 0.0,
        val extremeMidi: Double? = null,
        val voicedSeconds: Double = 0.0,
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
    // The run in progress: consecutive valid frames that stay within the tolerance of its reference.
    private var runReferenceMidi: Double? = null
    private val runMidis = mutableListOf<Double>()
    private var runStart: Double? = null
    private var runEnd: Double? = null
    // The most extreme qualifying run seen so far in this stage; comfort keeps its first long enough run.
    private var bestMidi: Double? = null
    private var bestStart: Double? = null
    private var bestEnd: Double? = null
    private var lastValidEnd: Double? = null
    private var voicedSeconds = 0.0
    private var message = "잠시 조용히 계세요"

    @Synchronized fun view() = View(phase, stages.size, currentMidi, runDuration(),
        now - stageStart, message,
        if (phase in setOf("down", "up")) (missionSeconds(phase) - (now - stageStart)).coerceAtLeast(0.0) else 0.0,
        if (phase in setOf("down", "up")) bestMidi else null, voicedSeconds)

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
            // A short dropout is tolerated by the run's own gap check; only the deadline ends a stage.
            currentMidi = null
            deadline()
            return view()
        }
        val midi = RangeProtocol.midi(frame.f0)
        currentMidi = midi
        if (phase in setOf("down", "up") && startMidi == null &&
            abs(midi - stages.first().endpointMidi) > HOLD_TOLERANCE_SEMITONES) {
            message = "먼저 편안한 음에서 시작해 주세요"
            deadline()
            return view()
        }
        val frameEnd = frame.t + frame.duration
        if (lastValidEnd != null && frame.t - lastValidEnd!! > .12) resetRun()
        lastValidEnd = frameEnd
        voicedSeconds += frame.duration.coerceAtMost(.04)
        if (startMidi == null) startMidi = midi
        if (runReferenceMidi == null || abs(midi - runReferenceMidi!!) > HOLD_TOLERANCE_SEMITONES) startRun(midi, frame.t, frameEnd)
        else extendRun(midi, frameEnd)
        if (phase == "comfort") {
            val settled = steady()
            if (runDuration() >= HOLD_SECONDS && settled != null) { keepRun(settled); complete(now) } else deadline()
            return view()
        }
        val direction = if (phase == "down") -1 else 1
        // Only a run that held its pitch long enough may claim the endpoint, so a single
        // octave misdetection or a cracked note never becomes the measured boundary.
        if (runDuration() >= EXTREME_HOLD_SECONDS) {
            val candidate = steady()
            // While the winning run keeps its pitch, widening its window gives the server more to verify.
            val sameRun = bestStart != null && bestStart == runStart
            if (candidate != null && (bestMidi == null || direction * (candidate - bestMidi!!) > 0 ||
                    sameRun && direction * (candidate - bestMidi!!) >= 0)) keepRun(candidate)
        }
        val reached = bestMidi?.let { direction * (it - startMidi!!) >= 2.0 } == true
        val overtime = now - stageStart >= missionSeconds(phase)
        message = when {
            overtime && voicedSeconds < MIN_VOICED_SECONDS ->
                "조금 더 불러 주세요. ${ceil(MIN_VOICED_SECONDS - voicedSeconds).toInt()}초 더 부르면 돼요"
            overtime && !usable() ->
                "아직 기록할 구간을 못 찾았어요. 조금 더 ${if (direction < 0) "낮게" else "높게"} 이어서 불러 주세요"
            reached -> "좋아요. 더 ${if (direction < 0) "낮은" else "높은"} 음까지 가 볼 수 있어요"
            else -> "편안한 음에서 천천히 ${if (direction < 0) "내려" else "올려"} 주세요"
        }
        deadline()
        return view()
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
        const val HOLD_SECONDS = 1.5
        const val HOLD_TOLERANCE_SEMITONES = 1.5
        /** The lower sweep is shorter; the upper sweep keeps its existing duration. */
        const val DOWN_MISSION_SECONDS = 10.0
        const val UP_MISSION_SECONDS = 15.0
        fun missionSeconds(phase: String): Double = when (phase) {
            "down" -> DOWN_MISSION_SECONDS
            "up" -> UP_MISSION_SECONDS
            else -> 0.0
        }
        /** Without a usable segment a sweep keeps listening, but never past this. */
        const val MAX_STAGE_SECONDS = 45.0
        /** A boundary candidate has to be sung steadily for at least this long. */
        const val EXTREME_HOLD_SECONDS = 0.3
        /** The endpoint may not be claimed from the very first instant of a stage. */
        const val ENDPOINT_OFFSET_SECONDS = 0.5
        /** Silence does not count: a sweep is not over until this much voice went into it. */
        const val MIN_VOICED_SECONDS = 5.0
        const val COMFORT_TIMEOUT_SECONDS = 30.0

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
        startMidi = null; bestMidi = null; bestStart = null; bestEnd = null
        resetRun()
        lastValidEnd = null; voicedSeconds = 0.0
        currentMidi = null
        message = when (name) {
            "noise" -> "잠시 조용히 계세요"
            "comfort" -> "편한 목소리로 '아'를 1.5초 내주세요"
            "down" -> "편안한 음에서 천천히 목소리를 내려주세요"
            else -> "편안한 음에서 천천히 목소리를 높여주세요"
        }
    }

    /**
     * The mission time is a floor, not a deadline. A sweep ends as soon as it has a usable
     * segment, and until then it keeps listening rather than throwing the attempt away.
     */
    private fun deadline() {
        if (phase == "comfort") { if (now - stageStart >= COMFORT_TIMEOUT_SECONDS) fail(); return }
        if (phase !in setOf("down", "up")) return
        val elapsed = now - stageStart
        if (elapsed < missionSeconds(phase)) return
        if (!usable()) { if (elapsed >= MAX_STAGE_SECONDS) fail(); return }
        complete(minOf(now, stageStart + MAX_STAGE_SECONDS + .4).coerceAtLeast(bestEnd ?: now))
    }

    /** Everything the stage owes the server: a steady boundary, real movement and real voice. */
    private fun usable(): Boolean {
        val direction = if (phase == "down") -1 else 1
        val origin = startMidi ?: return false
        val endpoint = bestMidi ?: return false
        val heldFrom = bestStart ?: return false
        return voicedSeconds >= MIN_VOICED_SECONDS && direction * (endpoint - origin) >= 2.0 &&
            heldFrom - stageStart >= ENDPOINT_OFFSET_SECONDS
    }

    private fun startRun(midi: Double, start: Double, end: Double) {
        runMidis.clear()
        runMidis += midi
        runReferenceMidi = midi
        runStart = start
        runEnd = end
    }
    private fun extendRun(midi: Double, end: Double) {
        runMidis += midi
        runEnd = end
    }
    private fun resetRun() { runReferenceMidi = null; runMidis.clear(); runStart = null; runEnd = null }
    private fun runDuration() = if (runStart != null && runEnd != null) runEnd!! - runStart!! else 0.0
    /**
     * The run's median, but only while the window around it would also satisfy the server's own
     * steadiness rule. A run anchored mid-glide can span the full tolerance, and a window the
     * server would reject is worse than no candidate at all.
     */
    private fun steady(): Double? {
        val candidate = RangeEvaluator.median(runMidis) ?: return null
        val within = runMidis.count { abs(it - candidate) <= HOLD_TOLERANCE_SEMITONES }
        return if (within >= runMidis.size * .9) candidate else null
    }
    private fun keepRun(midi: Double) {
        bestMidi = midi
        bestStart = runStart
        bestEnd = runEnd
    }
    private fun fail() {
        failedPhase = phase
        phase = "failed"
        message = "목소리를 확인하지 못했어요. 이 단계만 다시 시도해 주세요"
    }

    private fun complete(time: Double) {
        val origin = startMidi ?: return
        val endpoint = bestMidi ?: return
        val heldFrom = bestStart ?: return
        val heldUntil = bestEnd ?: return
        stages.add(FreeRangeStage(phase, stageStart, time, origin, endpoint, heldFrom, heldUntil))
        nextPhase = if (phase == "comfort") "down" else "up"
        phase = if (stages.size == 3) "done" else "transition"
        message = if (phase == "done") "음역 측정을 마쳤어요" else "${stages.size}/3 완료 · 다음 단계를 시작해 주세요"
    }
}
