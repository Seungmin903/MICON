package com.micon.app.range

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Samples describe [t, t + duration). Missing time never inherits the previous pitch. */
object RangeEvaluator {
    fun voiced(f: RangeFrame, floor: Double): Boolean = f.f0.isFinite() && f.f0 in 60.0..1150.0 &&
        f.clarity >= RangeProtocol.CLARITY && f.rmsLow >= floor

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val i = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[i - 1] + sorted[i]) / 2 else sorted[i]
    }

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var weight = 0.0
        for ((i, value) in sorted.withIndex()) {
            weight += value.second
            if (abs(weight - half) < 1e-9 && i + 1 < sorted.size) return (value.first + sorted[i + 1].first) / 2
            if (weight >= half) return value.first
        }
        return sorted.last().first
    }

    fun evaluate(frames: List<RangeFrame>, target: Int, start: Double, end: Double, floor: Double): RangeMetrics {
        require(end > start)
        var coveredUntil = start
        var voicedTime = 0.0
        var inTuneTime = 0.0
        var clipped = 0.0
        var hold = 0.0
        var longest = 0.0
        var continuous = 0.0
        var longestContinuous = 0.0
        var continuousEnd: Double? = null
        var lastGoodEnd: Double? = null
        val errors = mutableListOf<Pair<Double, Double>>()
        for (f in frames) {
            if (!f.t.isFinite() || !f.duration.isFinite() || f.duration <= 0) continue
            val left = max(max(start, f.t), coveredUntil)
            val right = min(end, f.t + min(f.duration, 0.04))
            if (right <= left) continue
            coveredUntil = right
            val dt = right - left
            clipped += f.clippedRatio.coerceIn(0.0, 1.0) * dt
            if (!voiced(f, floor)) { continuous = 0.0; continuousEnd = null; continue }
            val error = 100 * (RangeProtocol.midi(f.f0) - target)
            voicedTime += dt
            errors.add(error to dt)
            if (abs(error) <= 45.0 + 1e-8) {
                inTuneTime += dt
                if (lastGoodEnd == null || left - lastGoodEnd > 0.1 + 1e-8) hold = 0.0
                hold += dt
                longest = max(longest, hold)
                lastGoodEnd = right
                if (f.clippedRatio <= .01 + 1e-8) {
                    if (continuousEnd == null || left - continuousEnd > 1e-8) continuous = 0.0
                    continuous += dt; continuousEnd = right
                    longestContinuous = max(longestContinuous, continuous)
                } else { continuous = 0.0; continuousEnd = null }
            } else { continuous = 0.0; continuousEnd = null }
        }
        val center = weightedMedian(errors)
        val spread = center?.let { c -> weightedMedian(errors.map { abs(it.first - c) to it.second })!! * 1.4826 }
        val length = end - start
        return RangeMetrics(weightedMedian(errors.map { abs(it.first) to it.second }), spread,
            (inTuneTime / length).coerceIn(0.0, 1.0), (voicedTime / length).coerceIn(0.0, 1.0), longest, clipped / length, longestContinuous)
    }
}
