package com.micon.app.range

import kotlin.math.*

/** v5: raw audio time, frozen robust reference, and completed (never prefix) records. */
class BufferedHoldTracker(private val target: Int, private val anchor: Int, private val rmsFloor: Double,
                          private val revised: Boolean = false) {
    private val startToleranceSemitones = if (revised) 2.0 else 1.0
    data class Record(val referenceStart: Double, val referenceEnd: Double, val referenceMidi: Double,
                      val start: Double, val end: Double, val metrics: RangeMetrics, val endReason: String,
                      val pitchCandidates: List<RangePitchCandidate> = emptyList())
    private val seed = mutableListOf<Pair<Double, Double>>()
    private val pitches = mutableListOf<Pair<Double, Double>>()
    private val corePitches = mutableListOf<Pair<Double, Double>>()
    private val candidates = mutableListOf<RangePitchCandidate>()
    private var seedStart: Double? = null
    private var start = 0.0
    private var end = 0.0
    private var covered = Double.NEGATIVE_INFINITY
    private var credited = 0.0
    private var buffer = 0.0
    private var outside = 0.0
    private var strict = 0.0
    private var strictMax = 0.0
    private var excursion = 0.0
    private var hardExcursion = 0.0
    private var clip = 0.0
    private var quietStart: Double? = null
    private var pendingEnd: Record? = null
    private var pendingDeadline: Double? = null
    var referenceMidi: Double? = null; private set
    var generation = 0; private set
    var zone = "acquiring"; private set
    var lastDecision = "acquiring"; private set
    var best: Record? = null; private set
    var ended: Record? = null; private set
    val observed = linkedSetOf<Int>()
    val current: Record? get() = record("in_progress")

    private fun reset() {
        seed.clear(); pitches.clear(); corePitches.clear(); candidates.clear(); seedStart = null; referenceMidi = null
        credited = 0.0; buffer = 0.0; outside = 0.0; strict = 0.0; strictMax = 0.0
        excursion = 0.0; hardExcursion = 0.0; clip = 0.0; generation++
    }
    private fun retain(r: Record?) {
        if (r != null && (best == null || r.metrics.recordedHoldSeconds > best!!.metrics.recordedHoldSeconds + EPS)) best = r
    }
    private fun finish(reason: String): Record? {
        val r = record(reason); retain(r); ended = r; reset(); lastDecision = reason; zone = reason
        return r
    }
    /** A checkpoint is read-only; it must not freeze a high-scoring prefix of the live run. */
    fun snapshot(reason: String): Record? {
        val live = record(reason)
        return if (live != null && (best == null || live.metrics.recordedHoldSeconds > best!!.metrics.recordedHoldSeconds + EPS)) live else best
    }
    private fun clearPendingEnd() { pendingEnd = null; pendingDeadline = null; quietStart = null }
    fun seal(reason: String) { finish(reason); clearPendingEnd() }

    fun accept(f: RangeFrame) {
        ended = null
        if (!f.t.isFinite() || !f.duration.isFinite() || f.duration <= 0) return
        var left = max(f.t, covered)
        val right = f.t + min(f.duration, .04)
        if (right <= left) return
        if (covered.isFinite() && left > covered + EPS) {
            finish("input_uncertain"); ended = null; clearPendingEnd()
        }
        covered = right
        val tracked = f.f0.isFinite() && f.f0 in 60.0..1150.0 && f.clarity.isFinite() && f.clarity >= RangeProtocol.CLARITY
        val low = !f.rmsLow.isFinite() || f.rmsLow < rmsFloor
        val clipped = !f.clippedRatio.isFinite() || f.clippedRatio > .01 + EPS
        val candidateMidi = if (tracked) RangeProtocol.midi(f.f0) else null
        // Missing/invalid samples in stabilization are not invented: at least .15 of
        // the fixed .2 seconds must still have valid in-range evidence.
        if (referenceMidi == null && seedStart != null && !clipped &&
            (!tracked || low || candidateMidi == null || abs(candidateMidi-target) > startToleranceSemitones + EPS)) {
            val seedEnd = seedStart!! + .2
            if (right >= seedEnd - EPS) {
                if (seed.sumOf { it.second } >= .15 - EPS) {
                    referenceMidi = median(seed); start = seedEnd; end = seedEnd
                    if (right > seedEnd + EPS) { finish("input_uncertain"); ended = null }
                } else reset()
            }
            zone = "acquiring"; lastDecision = "reference_waiting"; return
        }
        if (!tracked || low || clipped) {
            zone = if (clipped) "clipping" else "input_uncertain"
            if (referenceMidi != null || seedStart != null) {
                pendingEnd = finish(zone)
                pendingDeadline = if (revised && pendingEnd != null) left + .3 else null
            }
            // Uncertain input preserves the record but never advances by itself.
            ended = null; lastDecision = zone
            if (clipped) clearPendingEnd()
            if (low && !tracked && !clipped) {
                if (quietStart == null) quietStart = left
                if (right - quietStart!! >= .12 - EPS) {
                    // Confirm on audio time, even if the threshold falls inside this frame.
                    val timely = !revised || (pendingDeadline != null && quietStart!! + .12 <= pendingDeadline!! + EPS)
                    ended = if (timely) pendingEnd?.copy(endReason = "voice_ended") else null
                    if (ended != null && best?.start == ended?.start && best?.end == ended?.end) best = ended
                    if (ended != null) lastDecision = "voice_ended"
                    clearPendingEnd()
                }
            } else { quietStart = null; if (!revised) clearPendingEnd() }
            if (revised && pendingDeadline != null && right >= pendingDeadline!! - EPS) clearPendingEnd()
            return
        }
        clearPendingEnd()
        val midi = RangeProtocol.midi(f.f0)
        if (referenceMidi == null) {
            zone = "acquiring"; lastDecision = "acquiring"
            if (abs(midi - target) > startToleranceSemitones + EPS) { reset(); lastDecision = "outside_start"; return }
            if (seedStart == null) seedStart = left
            f.pitchCandidate?.let { candidates.add(it) }
            val seedEnd = seedStart!! + .2
            val stop = min(right, seedEnd)
            if (stop > left) seed.add(midi to (stop - left))
            if (right < seedEnd - EPS) return
            if (seed.sumOf { it.second } < .15 - EPS) { reset(); return }
            referenceMidi = median(seed); start = seedEnd; end = seedEnd
            left = max(left, seedEnd)
            if (right <= left + EPS) return
        }
        val cents = abs(midi - referenceMidi!!) * 100
        f.pitchCandidate?.let { if (candidates.lastOrNull()?.t != it.t) candidates.add(it) }
        zone = when { cents <= 45 + EPS -> "normal"; cents <= 100 + EPS -> "buffer"; else -> "outside" }
        var stop = min(right, start + 2.5)
        if (zone != "normal") stop = min(stop, left + max(0.0, .5 - excursion))
        if (zone == "outside") stop = min(stop, left + max(0.0, .1 - hardExcursion))
        val dt = max(0.0, stop - left)
        pitches.add(midi to dt); clip = max(clip, f.clippedRatio)
        when (zone) {
            "normal" -> {
                credited += dt; strict += dt; strictMax = max(strictMax, strict)
                corePitches.add(midi to dt); excursion = 0.0; hardExcursion = 0.0
            }
            "buffer" -> { buffer += dt; strict = 0.0; excursion += dt; hardExcursion = 0.0 }
            else -> { outside += dt; strict = 0.0; excursion += dt; hardExcursion += dt }
        }
        end = stop; lastDecision = zone
        val reason = when {
            hardExcursion >= .1 - EPS -> "outside_timeout"
            excursion >= .5 - EPS -> "buffer_timeout"
            end >= start + 2.5 - EPS -> "max_duration"
            else -> null
        }
        if (reason != null) finish(reason)
    }

    private fun record(reason: String): Record? {
        val ref = referenceMidi ?: return null
        if (credited <= EPS || end <= start || corePitches.isEmpty()) return null
        val actual = median(corePitches)
        val duration = end - start
        val spread = sqrt(pitches.sumOf { ((it.first - actual) * 100).pow(2) * it.second } / pitches.sumOf { it.second })
        val referenceRms = sqrt(pitches.sumOf { ((it.first - ref) * 100).pow(2) * it.second } / pitches.sumOf { it.second })
        val time = 50 * (credited / 2.5).coerceIn(0.0, 1.0)
        val steady = if (revised) time * (1 - referenceRms / 45).coerceIn(0.0, 1.0)
            else 50 * (1 - spread / 45).coerceIn(0.0, 1.0) * credited.coerceIn(0.0, 1.0)
        val penalty = min(20.0, 40 * (buffer + 2 * outside))
        val note = credit(actual, target, anchor)
        if (note != null) observed.add(note)
        val m = RangeMetrics(median(pitches.map { abs(it.first - ref) * 100 to it.second }), spread,
            credited / duration, (credited + buffer + outside) / duration, strictMax, clip, strictMax,
            actual, note, max(0.0, time + steady - penalty), time, steady,
            credited, buffer, outside, penalty, ref, if (revised) referenceRms else null)
        return Record(seedStart!!, start, ref, start, end, m, reason, candidates.toList())
    }
    companion object {
        private const val EPS = 1e-8
        fun credit(actual: Double, target: Int, anchor: Int): Int? {
            if (!actual.isFinite()) return null
            val midi = when { target > anchor -> floor(actual + EPS).toInt()
                target < anchor -> ceil(actual - EPS).toInt(); else -> floor(actual + .5).toInt() }
            return midi.takeIf { it in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI }
        }
        fun median(values: List<Pair<Double, Double>>): Double {
            val sorted = values.filter { it.second > 0 }.sortedBy { it.first }
            val half = sorted.sumOf { it.second } / 2
            var total = 0.0
            for ((i, p) in sorted.withIndex()) {
                total += p.second
                if (abs(total - half) < 1e-9 && i + 1 < sorted.size) return (p.first + sorted[i + 1].first) / 2
                if (total >= half) return p.first
            }
            return sorted.last().first
        }
    }
}
