package com.micon.app.range

import kotlin.math.*

/** Measurement-only temporal guard. Never uses a target note or alters confidence. */
class GuidedPitchSelector {
    data class Selection(val frame: RangeFrame, val reason: String, val recentMidi: Double?)
    private val recent = ArrayDeque<RangeFrame>()
    private var context: String? = null
    fun reset() { recent.clear(); context = null }
    fun select(corrected: RangeFrame, initialF0: Double?, initialClarity: Double?,
               floor: Double, key: String, suppressed: Boolean = false): Selection {
        if (context != key || suppressed) recent.clear()
        context = key
        recent.removeAll { it.t + it.duration <= corrected.t - .1 + 1e-8 }
        val history = recent.map { RangeProtocol.midi(it.f0) to
            max(0.0, min(it.t + it.duration, corrected.t) - max(it.t, corrected.t - .1)) }.filter { it.second > 0 }
        val center = if (history.sumOf { it.second } >= .06 - 1e-8) BufferedHoldTracker.median(history) else null
        val initial = if (initialF0 != null && initialClarity != null) corrected.copy(f0 = initialF0, clarity = initialClarity) else null
        val preserve = !suppressed && center != null && initial != null && RangeEvaluator.voiced(initial, floor) &&
            initial.clippedRatio <= .01 + 1e-8 && abs(RangeProtocol.midi(initial.f0) - center) <= 1 + 1e-8 &&
            (!corrected.f0.isFinite() || corrected.f0 <= 0 || abs(RangeProtocol.midi(corrected.f0) - center) > 1 + 1e-8)
        val chosen = if (preserve) initial!! else corrected
        if (!suppressed && RangeEvaluator.voiced(chosen, floor) && chosen.clippedRatio <= .01 + 1e-8) recent.addLast(chosen)
        val reason = when { preserve -> "initial_continuity"; suppressed -> "suppressed";
            center == null -> "corrected_no_history"; else -> "corrected" }
        val evidence = if (initial != null) RangePitchCandidate(corrected.t,initial.f0,initial.clarity,
            corrected.f0,corrected.clarity,chosen.f0,reason) else null
        return Selection(chosen.copy(pitchCandidate = evidence), reason, center)
    }
}
