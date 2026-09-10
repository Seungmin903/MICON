package com.micon.app.range

/** develop's five-frame median is presentation only; never feed it to RangeEvaluator. */
class PitchDisplayFilter {
    private val history = ArrayDeque<Double>()
    private var cue = -1
    private var target: Int? = null
    private var lastTime = Double.NEGATIVE_INFINITY

    fun reset() { history.clear(); cue = -1; target = null; lastTime = Double.NEGATIVE_INFINITY }

    fun update(midi: Double?, audioTime: Double, cueId: Int, targetMidi: Int?, suppressed: Boolean): Double? {
        if (cue != cueId || target != targetMidi || audioTime < lastTime || audioTime - lastTime > .1 || suppressed) history.clear()
        cue = cueId; target = targetMidi; lastTime = audioTime
        if (suppressed) return null
        if (midi != null && midi.isFinite()) {
            history.addLast(midi)
            if (history.size > 5) history.removeFirst()
        } else if (history.isNotEmpty()) history.removeFirst()
        return if (history.size >= 3) RangeEvaluator.median(history.toList()) else null
    }
}
