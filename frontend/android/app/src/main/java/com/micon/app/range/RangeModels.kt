package com.micon.app.range

import kotlin.math.ln

/** Protocol values are duplicated in the Python evaluator and checked with shared fixtures. */
object RangeProtocol {
    const val VERSION = "guided-range-v7"
    fun supports(version: String) = version in setOf("guided-range-v1", "guided-range-v2", "guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", VERSION)
    fun buffered(version: String) = version in setOf("guided-range-v5", "guided-range-v6", "guided-range-v7")
    const val FULL_HOLD_SECONDS = 2.5
    const val PASS_HOLD_SECONDS = 1.0
    const val MIN_MIDI = 36
    const val MAX_MIDI = 84
    const val WINDOW = 2.0
    const val ADVANCE_HOLD_SECONDS = 1.0
    const val CLARITY = 0.8
    const val START_TOLERANCE_SEMITONES = 1.0
    fun midi(hz: Double): Double = 69 + 12 * ln(hz / 440.0) / ln(2.0)
}

data class RangeFrame(
    val t: Double, val duration: Double, val f0: Double, val clarity: Double,
    val rms: Double, val rmsLow: Double = rms, val clippedRatio: Double = 0.0,
    val pitchCandidate: RangePitchCandidate? = null,
)

data class RangePitchCandidate(val t: Double, val initialF0: Double, val initialClarity: Double,
    val correctedF0: Double, val correctedClarity: Double, val selectedF0: Double, val reason: String)

data class RangeMetrics(
    val medianAbsErrorCents: Double?, val pitchSpreadCents: Double?,
    val inTuneRatio: Double, val voicedRatio: Double, val longestHoldSeconds: Double,
    val clippedRatio: Double = 0.0,
    val continuousHoldSeconds: Double = 0.0,
    val actualMidi: Double? = null,
    val creditedMidi: Int? = null,
    val stabilityScore: Double? = null,
    val timeScore: Double? = null,
    val steadinessScore: Double? = null,
    val creditedHoldSeconds: Double? = null,
    val bufferSeconds: Double? = null,
    val outsideSeconds: Double? = null,
    val excursionPenalty: Double? = null,
    val referenceMidi: Double? = null,
    val referenceRmsCents: Double? = null,
) {
    val recordedHoldSeconds: Double get() = creditedHoldSeconds ?: continuousHoldSeconds
    val passed: Boolean get() = if (stabilityScore != null) creditedMidi != null &&
        recordedHoldSeconds >= RangeProtocol.PASS_HOLD_SECONDS - 1e-8 && clippedRatio <= .01 + 1e-8
        else medianAbsErrorCents != null && pitchSpreadCents != null &&
        medianAbsErrorCents <= 25.0 + 1e-8 && pitchSpreadCents <= 20.0 + 1e-8 &&
        inTuneRatio >= 0.9 - 1e-8 && voicedRatio >= 0.9 - 1e-8 &&
        longestHoldSeconds >= 1.5 - 1e-8 && clippedRatio <= 0.01 + 1e-8
}

data class RangeAttempt(
    val attemptId: String, val targetMidi: Int, val direction: String,
    val cueStart: Double, val cueEnd: Double, val responseStart: Double, val responseEnd: Double,
    val evaluationStart: Double?, val evaluationEnd: Double?,
    val clientStatus: String, val metrics: RangeMetrics?, val retryOf: String? = null,
    val selfReportedRegister: String = "unknown",
    val visitId: String? = null, val advanceReason: String? = null,
    val progressQualified: Boolean = false, val selectedForVisit: Boolean = true,
    val progressEvaluationStart: Double? = null, val progressEvaluationEnd: Double? = null,
    val searchOutcome: String? = null,
    val observedMidis: List<Int> = emptyList(),
    val referenceStart: Double? = null, val referenceEnd: Double? = null,
    val referenceMidi: Double? = null, val runEndReason: String? = null,
    val pitchCandidates: List<RangePitchCandidate> = emptyList(),
    val probeStage: String? = null, val anchorMidi: Int? = null,
)

data class RegisterEvent(val t: Double, val register: String)

fun RangeSubmission.needsPreparationRetry(): Boolean = protocolVersion == "guided-range-v1" && mode == "basic" &&
    stopReason in setOf("anchor_not_matched", "input_quality", "model_limit") &&
    attempts.none { it.clientStatus == "passed" }

data class RangeSubmission(
    val sessionId: String, val measurementType: String = "guided_range",
    val protocolVersion: String = RangeProtocol.VERSION, val mode: String = "basic",
    val parentAnalysisId: String? = null, val startMidi: Int?,
    val minMidi: Int = RangeProtocol.MIN_MIDI, val maxMidi: Int = RangeProtocol.MAX_MIDI,
    val noiseRms: Double, val durationSeconds: Double, val stopReason: String,
    val lowerBoundaryStatus: String, val upperBoundaryStatus: String,
    val attempts: List<RangeAttempt>, val registerEvents: List<RegisterEvent>, val keepOriginal: Boolean = false,
    val sampling: RangeSampling? = null,
)

data class RangeMap(val overall: List<Int>? = null, val stable: List<Int>? = null,
                    val recommended: List<Int>? = null)

data class RangeNote(
    val midi: Int, val status: String, val metrics: RangeMetrics? = null,
    val registerEstimate: String = "unclear", val selfReportedRegister: String = "unknown",
    val successfulAttempts: Int = 0, val attempts: Int = 0,
)

data class RangeReport(
    val protocolVersion: String, val notes: List<RangeNote>, val lowMidi: Int?, val highMidi: Int?,
    val stableIntervals: List<List<Int>>, val stopReason: String,
    val lowerBoundaryStatus: String, val upperBoundaryStatus: String,
    val availability: String, val analysisId: String? = null,
    val verification: String = "server_verified",
    val sampling: RangeSampling? = null,
    val rangeMap: RangeMap? = null,
    val backendSessionId: Long? = null,
)

data class RangeAnalysis(val analysisId: String, val status: String, val error: String? = null)
