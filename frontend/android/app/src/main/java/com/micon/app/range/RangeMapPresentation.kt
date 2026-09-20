package com.micon.app.range

/** Restored continuous overview. Per-note scores and verified intervals remain independent. */
fun RangeReport.continuousRangeMap(): RangeMap {
    fun bounds(midis: List<Int>): List<Int>? = midis.takeIf { it.isNotEmpty() }?.let { listOf(it.min(),it.max()) }
    val measured = notes.filter { it.metrics != null && it.status in setOf("passed","reconfirmed","not_passed","inconsistent") }
    val overall = rangeMap?.overall ?: bounds(measured.map { it.midi })
        ?: lowMidi?.let { lo -> highMidi?.takeIf { it >= lo }?.let { hi -> listOf(lo, hi) } }
    val held = if (protocolVersion == RangeProtocol.FREE_VERSION && stableLowMidi != null && stableHighMidi != null)
        listOf(kotlin.math.floor(stableLowMidi).toInt(), kotlin.math.ceil(stableHighMidi).toInt()) else null
    val stable = held ?: rangeMap?.stable ?: bounds(notes.filter { it.status in setOf("passed","reconfirmed") }.map { it.midi })
    val recommended = if (protocolVersion == RangeProtocol.FREE_VERSION) stable
        else rangeMap?.recommended ?: stable?.takeIf { it[1]-it[0] >= 4 }?.let { listOf(it[0]+2,it[1]-2) }
    return RangeMap(overall,stable,recommended)
}
