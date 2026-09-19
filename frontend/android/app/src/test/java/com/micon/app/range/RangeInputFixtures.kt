package com.micon.app.range

fun usableRangeSubmission(): RangeSubmission {
    val metrics = RangeMetrics(0.0, 0.0, 1.0, 1.0, 1.2, continuousHoldSeconds = 1.2,
        actualMidi = 60.0, creditedMidi = 60, stabilityScore = 48.0, creditedHoldSeconds = 1.2,
        bufferSeconds = 0.0, outsideSeconds = 0.0, excursionPenalty = 0.0, referenceMidi = 60.0, referenceRmsCents = 0.0)
    val a = RangeAttempt("a1", 60, "anchor", 0.0, .5, .75, 2.55, 1.2, 2.4, "passed", metrics,
        visitId = "v1", advanceReason = "threshold", progressQualified = true, searchOutcome = "reached",
        referenceStart = 1.0, referenceEnd = 1.2, referenceMidi = 60.0, runEndReason = "voice_ended",
        probeStage = "anchor", anchorMidi = 60)
    return RangeSubmission("quality-range", startMidi = 60, noiseRms = .0001, durationSeconds = 4.0,
        stopReason = "completed", lowerBoundaryStatus = "not_probed", upperBoundaryStatus = "not_probed",
        attempts = listOf(a), registerEvents = emptyList(),
        sampling = RangeSampling(basicCompleted = true, measuredNoteCount = 1, strategy = "adaptive_6", coarseStep = 6))
}
