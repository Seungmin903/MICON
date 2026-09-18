package com.micon.app.range

/** Navigation eligibility only. This snapshot must never replace a model-verified report. */
fun RangeSubmission.onboardingPreview(): RangeReport? {
    if (protocolVersion != RangeProtocol.VERSION || sampling?.basicCompleted != true ||
        sampling.measuredNoteCount <= 0 || stopReason !in setOf("completed", "user_stopped", "time_limit", "model_limit")) return null
    return deviceReport().takeIf { it.availability == "available" }
}

/** Summarises the v7 decisions already evaluated on device; it does not infer unmeasured notes. */
fun RangeSubmission.deviceReport(): RangeReport {
    require(protocolVersion == RangeProtocol.VERSION)
    val measured = attempts.filter { it.selectedForVisit && it.clientStatus !in setOf("interrupted", "unmeasured") }
        .groupBy { it.metrics?.creditedMidi ?: it.targetMidi }
    val notes = RangeKeyboardState.notes(emptyList(), attempts).map { note ->
        val metrics = measured[note.midi].orEmpty().filter { it.clientStatus == "passed" }
            .maxByOrNull { it.metrics?.recordedHoldSeconds ?: 0.0 }?.metrics
        note.copy(metrics = metrics)
    }
    val confirmed = notes.filter { it.successfulAttempts > 0 }.map { it.midi }
    val low = confirmed.minOrNull()
    val high = confirmed.maxOrNull()
    val usable = low != null && high != null && stopReason !in setOf("input_quality", "app_interrupted")
    return RangeReport(protocolVersion, notes, low, high, emptyList(), stopReason,
        lowerBoundaryStatus, upperBoundaryStatus, if (usable) "available" else "unavailable",
        analysisId = sessionId, verification = "device_measured", sampling = sampling,
        rangeMap = RangeMap(overall = if (low != null && high != null) listOf(low, high) else null))
}
