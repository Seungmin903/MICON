package com.micon.app.range

import kotlin.math.*

/** A run has one frozen reference. Silence, gaps, clipping and drift break it; no octave folding. */
class RelativeHoldTracker(private val target: Int, private val anchor: Int, private val floor: Double) {
    data class Record(val start: Double, val end: Double, val metrics: RangeMetrics)
    private val run = mutableListOf<Pair<Double, Double>>()
    private var reference: Double? = null
    private var start = 0.0
    private var end: Double? = null
    private var covered = Double.NEGATIVE_INFINITY
    val observed = linkedSetOf<Int>()
    var best: Record? = null; private set
    var current: Record? = null; private set
    /** Observations only: never used as input to scoring or progress. */
    var lastDecision: String = "not_evaluated"; private set
    val referenceMidi: Double? get() = reference

    private fun reset() { run.clear(); reference = null; end = null; current = null }

    fun accept(f: RangeFrame) {
        lastDecision = "ignored_frame"
        if (!f.t.isFinite() || !f.duration.isFinite() || f.duration <= 0) return
        val left = max(f.t, covered)
        val right = f.t + min(f.duration, .04)
        if (right <= left) return
        covered = right
        val midi = if (RangeEvaluator.voiced(f, floor)) RangeProtocol.midi(f.f0) else null
        if (midi == null || abs(midi - target) > 1.0 + 1e-8 || f.clippedRatio > .01 + 1e-8) {
            lastDecision = when {
                midi == null -> "invalid_input"
                abs(midi - target) > 1.0 + 1e-8 -> "outside_target"
                else -> "clipping"
            }
            reset(); return
        }
        val note = credit(midi, target, anchor)
        if (note == null) { lastDecision = "unsupported_note"; reset(); return }
        observed.add(note)
        if (end == null || left - end!! > 1e-8 || abs(midi - reference!!) * 100 > 45 + 1e-8) {
            lastDecision = when {
                end == null -> "run_started"
                left - end!! > 1e-8 -> "timestamp_gap"
                else -> "reference_drift"
            }
            reset(); reference = midi; start = left
        } else lastDecision = "continued"
        // The full-score duration bounds the buffer and preserves the first tied record.
        val limit = min(right, start + RangeProtocol.FULL_HOLD_SECONDS)
        if (limit <= left) return
        run.add(midi to (limit - left)); end = limit
        val actual = weightedMedian(run)
        val duration = limit - start
        val spread = sqrt(run.sumOf { ((it.first - actual) * 100).pow(2) * it.second } / duration)
        val time = 50 * (duration / RangeProtocol.FULL_HOLD_SECONDS).coerceIn(0.0, 1.0)
        val steady = 50 * (1 - spread / 45).coerceIn(0.0, 1.0) * (duration / RangeProtocol.PASS_HOLD_SECONDS).coerceIn(0.0, 1.0)
        val metrics = RangeMetrics(weightedMedian(run.map { abs(it.first - actual) * 100 to it.second }),
            spread, 1.0, 1.0, duration, f.clippedRatio, duration, actual, credit(actual, target, anchor),
            time + steady, time, steady)
        current = Record(start, limit, metrics)
        if (best == null || duration > best!!.metrics.continuousHoldSeconds + 1e-8) best = current
    }

    companion object {
        fun credit(actual: Double, target: Int, anchor: Int): Int? {
            if (!actual.isFinite() || abs(actual - target) > 1 + 1e-8) return null
            val midi = when { target > anchor -> floor(actual + 1e-8).toInt()
                target < anchor -> ceil(actual - 1e-8).toInt()
                else -> floor(actual + .5).toInt() }
            return midi.takeIf { it in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI && abs(it - target) <= 1 }
        }
        private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
            val sorted = values.sortedBy { it.first }; val half = sorted.sumOf { it.second } / 2
            var total = 0.0
            for ((i, pair) in sorted.withIndex()) {
                total += pair.second
                if (abs(total - half) < 1e-9 && i + 1 < sorted.size) return (pair.first + sorted[i + 1].first) / 2
                if (total >= half) return pair.first
            }
            return sorted.last().first
        }
    }
}
