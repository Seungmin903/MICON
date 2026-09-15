package com.micon.app.range

/** Trim a crash checkpoint to durable PCM while retaining an earlier best window in a long visit. */
fun RangeSubmission.recoverAudio(available: Double): RangeSubmission {
    require(available > 0 && available.isFinite())
    if (RangeProtocol.buffered(protocolVersion) && attempts.any { it.runEndReason == "checkpoint" }) {
        val durable = attempts.map { a -> if (a.runEndReason == "checkpoint") a.copy(
            metrics = null, clientStatus = "insufficient_quality", evaluationStart = null, evaluationEnd = null,
            referenceStart = null, referenceEnd = null, referenceMidi = null, runEndReason = null,
            pitchCandidates = emptyList(), observedMidis = emptyList()) else a }
        val selected = durable.groupBy { it.visitId }.mapValues { (_, group) ->
            group.filter { it.metrics != null }.maxByOrNull { it.metrics!!.recordedHoldSeconds } ?: group.last()
        }
        return copy(stopReason = "app_interrupted", attempts = durable.map { a ->
            val chosen = selected[a.visitId]!!
            val wasOpen = attempts.any { it.visitId == a.visitId && it.runEndReason == "checkpoint" }
            if (wasOpen) a.copy(selectedForVisit = a.attemptId == chosen.attemptId, advanceReason = "app_interrupted",
                progressQualified = chosen.metrics?.passed == true,
                searchOutcome = if (chosen.metrics?.passed == true) "reached" else null) else a
        }).recoverAudio(available)
    }
    // Buffered PCM may extend beyond a crash checkpoint; keep the original and its actual duration.
    if (available >= durationSeconds) return if (RangeProtocol.buffered(protocolVersion)) copy(durationSeconds = available) else this
    if (protocolVersion == "guided-range-v1") return copy(durationSeconds = available, stopReason = "app_interrupted",
        attempts = attempts.filter { it.responseEnd <= available }, registerEvents = registerEvents.filter { it.t <= available })
    val trimmedAudio = attempts.mapNotNull { a ->
        if (a.cueStart >= available) null
        else if (a.responseEnd <= available) a
        else if (a.responseStart >= available) a.copy(cueEnd = available, responseStart = available, responseEnd = available,
            evaluationStart = null, evaluationEnd = null, metrics = null, clientStatus = "unmeasured", advanceReason = "app_interrupted")
        else {
            val keep = a.evaluationEnd?.let { it <= available } == true
            a.copy(responseEnd = available, evaluationStart = if (keep) a.evaluationStart else null,
                evaluationEnd = if (keep) a.evaluationEnd else null, metrics = if (keep) a.metrics else null,
                clientStatus = if (keep) a.clientStatus else "unmeasured", advanceReason = "app_interrupted")
        }
    }
    val trimmed = trimmedAudio.map { a ->
        if (a.progressEvaluationEnd?.let { it > available } == true) a.copy(progressEvaluationStart = null, progressEvaluationEnd = null) else a
    }.map { a ->
        if (a.evaluationStart == null) a.copy(referenceStart = null, referenceEnd = null, referenceMidi = null,
            runEndReason = null, pitchCandidates = emptyList()) else a
    }
    val selected = trimmed.groupBy { it.visitId }.mapValues { (_, group) ->
        group.filter { it.metrics != null }.maxByOrNull { if (protocolVersion in setOf("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")) it.metrics!!.recordedHoldSeconds else it.metrics!!.inTuneRatio } ?: group.last()
    }
    if (protocolVersion in setOf("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")) return copy(durationSeconds = available, stopReason = "app_interrupted",
        attempts = trimmed.map { a ->
            val chosen = selected[a.visitId]!!
            val reason = if (attempts.any { it.visitId == a.visitId && it.responseEnd > available }) "app_interrupted" else a.advanceReason
            val passed = chosen.metrics?.passed == true && reason != "anchor_reset"
            a.copy(selectedForVisit = a.attemptId == chosen.attemptId, advanceReason = reason,
                progressQualified = passed, progressEvaluationStart = null, progressEvaluationEnd = null,
                searchOutcome = if (passed) "reached" else if (reason == "manual") "manual_failed" else null,
                observedMidis = if (attempts.first { it.attemptId == a.attemptId }.responseEnd > available)
                    listOfNotNull(a.metrics?.creditedMidi) else a.observedMidis)
        }, registerEvents = registerEvents.filter { it.t <= available })
    return copy(durationSeconds = available, stopReason = "app_interrupted",
        attempts = trimmed.map { a -> a.copy(selectedForVisit = a.attemptId == selected[a.visitId]?.attemptId,
            progressQualified = if (protocolVersion == "guided-range-v2") selected[a.visitId]?.metrics?.inTuneRatio?.let { it >= .5 - 1e-8 } == true
                else trimmed.any { it.visitId == a.visitId && it.progressEvaluationStart != null },
            searchOutcome = if (protocolVersion == "guided-range-v3" && a.searchOutcome == "reached" && trimmed.none { it.visitId == a.visitId && it.progressEvaluationStart != null }) null else a.searchOutcome,
            advanceReason = if (protocolVersion == "guided-range-v3" && a.advanceReason == "threshold" && trimmed.none { it.visitId == a.visitId && it.progressEvaluationStart != null }) "app_interrupted" else a.advanceReason) },
        registerEvents = registerEvents.filter { it.t <= available })
}
