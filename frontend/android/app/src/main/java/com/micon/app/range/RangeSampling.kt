package com.micon.app.range

/** Sampling decisions are separate from verified note stability. */
data class RangeSampling(
    val basicCompleted: Boolean = false,
    val refinementStatus: String = "not_started",
    val skippedCentralIntervals: List<List<Int>> = emptyList(),
    val upperLastSuccess: Int? = null, val lowerLastSuccess: Int? = null,
    val upperEnd: Int? = null, val lowerEnd: Int? = null,
    val measuredNoteCount: Int = 0,
    val strategy: String? = null, val coarseStep: Int? = null,
    val upperFirstFailure: Int? = null, val lowerFirstFailure: Int? = null,
    // Ordered as [passed target, failed target], including for descending search.
    val upperBoundaryPair: List<Int>? = null, val lowerBoundaryPair: List<Int>? = null,
)

object RangeRefinement {
    fun targets(anchor: Int, sampling: RangeSampling, measured: Set<Int>): List<Int> {
        val high = sampling.upperLastSuccess ?: anchor
        val low = sampling.lowerLastSuccess ?: anchor
        val upper = sampling.upperEnd ?: high
        val lower = sampling.lowerEnd ?: low
        val queue = linkedSetOf(high, low)
        if (high < upper) queue.addAll((high + 1..upper).filter { it !in measured })
        if (low > lower) queue.addAll((low - 1 downTo lower).filter { it !in measured })
        if (lower <= upper) queue.addAll((lower..upper).filter { it !in measured })
        return queue.filter { it in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI }
    }
}
