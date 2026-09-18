package com.micon.app.range

import kotlin.math.abs

/** Display-only stabilization. RangeEvaluator and every persisted/model input keep using raw frames. */
class PitchDisplayFilter {
    private val history = ArrayDeque<Double>()
    private var cue = -1
    private var target: Int? = null
    private var lastTime = Double.NEGATIVE_INFINITY
    private var displayed: Double? = null

    fun reset() {
        clearDisplay()
        cue = -1
        target = null
        lastTime = Double.NEGATIVE_INFINITY
    }

    fun update(midi: Double?, audioTime: Double, cueId: Int, targetMidi: Int?, suppressed: Boolean): Double? {
        if (cue != cueId || target != targetMidi || audioTime < lastTime || audioTime - lastTime > .1 || suppressed) clearDisplay()
        cue = cueId; target = targetMidi; lastTime = audioTime
        if (suppressed) return null
        if (midi != null && midi.isFinite()) {
            history.addLast(midi)
            if (history.size > 5) history.removeFirst()
        } else if (history.isNotEmpty()) history.removeFirst()
        if (history.size < 3) return null
        val median = RangeEvaluator.median(history.toList()) ?: return null
        val previous = displayed
        val next = when {
            previous == null -> median
            abs(median - previous) <= DISPLAY_DEADBAND_SEMITONES -> previous
            else -> previous + (median - previous) * DISPLAY_SMOOTHING
        }
        displayed = next
        return next
    }

    private fun clearDisplay() {
        history.clear()
        displayed = null
    }

    private companion object {
        const val DISPLAY_DEADBAND_SEMITONES = .04 // Four cents of display jitter stays still.
        const val DISPLAY_SMOOTHING = .25
    }
}
