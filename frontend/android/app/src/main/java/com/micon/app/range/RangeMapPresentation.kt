package com.micon.app.range

/** Restored continuous overview. Per-note scores and verified intervals remain independent. */
fun RangeReport.continuousRangeMap(): RangeMap {
    rangeMap?.let { return it }
    fun bounds(midis: List<Int>): List<Int>? = midis.takeIf { it.isNotEmpty() }?.let { listOf(it.min(),it.max()) }
    val measured = notes.filter { it.metrics != null && it.status in setOf("passed","reconfirmed","not_passed","inconsistent") }
    val overall = bounds(measured.map { it.midi })
    val stable = bounds(notes.filter { it.status in setOf("passed","reconfirmed") }.map { it.midi })
    val recommended = stable?.takeIf { it[1]-it[0] >= 4 }?.let { listOf(it[0]+2,it[1]-2) }
    return RangeMap(overall,stable,recommended)
}
