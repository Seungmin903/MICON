package com.micon.app.range

/** Local history of requested keys, independent of the credited pitch in a model report. */
internal data class CompletedRangeTargets(val analysisId: String, val protocolVersion: String, val targets: Set<Int>)

internal fun RangeSubmission.completedTargets(): Set<Int> = attempts
    .filter { it.selectedForVisit && it.advanceReason in setOf("threshold", "manual") }
    .map { it.targetMidi }.toSet()

internal fun RangeReport.completedNoteFallback(): Set<Int> = notes
    .filter { it.attempts > 0 || it.metrics != null || it.successfulAttempts > 0 }.map { it.midi }.toSet()
