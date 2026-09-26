package com.micon.app.range

/** Navigation eligibility only. This snapshot must never replace a model-verified report. */
fun RangeSubmission.onboardingPreview(): RangeReport? {
    if (protocolVersion == RangeProtocol.FREE_VERSION) {
        if (RangeInputQuality.metadataProblem(this) != null || stopReason != "completed") return null
        return deviceReport().takeIf { it.availability == "available" }
    }
    if (RangeInputQuality.metadataProblem(this) != null || attempts.none {
            it.selectedForVisit && it.clientStatus == "passed" && it.metrics?.passed == true &&
                it.advanceReason != "anchor_reset" && it.runEndReason !in setOf("checkpoint", "in_progress")
        }) return null
    if (protocolVersion != RangeProtocol.VERSION || sampling?.basicCompleted != true ||
        sampling.measuredNoteCount <= 0 || stopReason !in setOf("completed", "user_stopped", "time_limit", "model_limit")) return null
    return deviceReport().takeIf { it.availability == "available" }
}

/** Summarises the v7 decisions already evaluated on device; it does not infer unmeasured notes. */
fun RangeSubmission.deviceReport(): RangeReport {
    if (protocolVersion == RangeProtocol.FREE_VERSION) {
        require(RangeInputQuality.metadataProblem(this) == null)
        val low = kotlin.math.floor(minOf(stages[0].endpointMidi, stages[1].endpointMidi)).toInt().coerceIn(36, 84)
        val high = kotlin.math.ceil(maxOf(stages[0].endpointMidi, stages[2].endpointMidi)).toInt().coerceIn(low, 84)
        return RangeReport(protocolVersion, emptyList(), low, high, emptyList(), stopReason,
            lowerBoundaryStatus, upperBoundaryStatus, "available", analysisId = sessionId,
            verification = "device_measured", rangeMap = RangeMap(overall = listOf(low, high)),
            stableLowMidi = stages[1].endpointMidi, stableHighMidi = stages[2].endpointMidi,
            voiceQuality = voiceQuality)
    }
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
