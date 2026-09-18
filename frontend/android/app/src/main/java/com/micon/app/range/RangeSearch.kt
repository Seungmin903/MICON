package com.micon.app.range

/** Legacy two-semitone sweep, or v6 eight-semitone probes followed by midpoint refinement. */
class RangeSearch(private val anchor: Int, val direction: Int, startOffset: Int = 2,
                  private val adaptive: Boolean = startOffset == 8,
                  private val maxMidpointProbes: Int? = null) {
    private var midpointProbes = 0
    private val coarseStep = startOffset
    private var candidate = anchor + direction * startOffset
    private var failures = 0
    var frontier: Int = anchor
        private set
    var lastSuccess: Int? = if (adaptive) anchor else null
        private set
    var firstFailure: Int? = null; private set
    private var failedEndpoint: Int? = null
    val stage: String get() = if (adaptive && firstFailure != null) "bisect" else "coarse"
    val boundaryPair: List<Int>? get() = failedEndpoint?.let { listOf(lastSuccess!!, it) }
    var boundary: String? = null
        private set

    init { require(direction == -1 || direction == 1); require(startOffset in listOf(2, 6, 8)) }
    fun next(): Int? {
        if (boundary != null) return null
        if (candidate !in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI) {
            boundary = "model_limit"; return null
        }
        return candidate
    }
    fun record(midi: Int, qualified: Boolean) {
        require(boundary == null && midi == candidate) { "Sweep results must follow target order" }
        if (adaptive) {
            if (firstFailure != null) midpointProbes++
            if (firstFailure == null) frontier = midi
            if (qualified) lastSuccess = midi
            else {
                if (firstFailure == null) firstFailure = midi
                failedEndpoint = midi
            }
            val failed = failedEndpoint
            if (failed == null) candidate = midi + direction * coarseStep
            else if (kotlin.math.abs(failed - lastSuccess!!) == 1) boundary = "boundary_refined"
            else if (maxMidpointProbes != null && midpointProbes >= maxMidpointProbes) boundary = "midpoint_sampled"
            else candidate = (failed + lastSuccess!!) / 2
            return
        }
        frontier = midi
        if (qualified) { failures = 0; lastSuccess = midi } else failures++
        candidate += direction * 2
        if (failures >= 2) boundary = "two_probe_failures"
    }
}
