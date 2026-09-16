package com.micon.app.ui.report

import com.micon.app.data.onboarding.ComponentStatus
import com.micon.app.data.onboarding.VoiceAnalysisProgress
import com.micon.app.data.onboarding.VoiceSessionStatus
import com.micon.app.range.RangeReport

/** Saving and artist lookup do not determine whether the timbre measurements are ready. */
fun VoiceAnalysisProgress.matchesRange(range: RangeReport): Boolean =
    range.backendSessionId?.let { sessionId == it.toString() } ?: true

fun VoiceAnalysisProgress.hasTimbreReport(range: RangeReport): Boolean =
    matchesRange(range) && range.availability == "available" && rangeStatus == "AVAILABLE" &&
        ecapa == ComponentStatus.COMPLETED && byol == ComponentStatus.COMPLETED &&
        sessionStatus in setOf(VoiceSessionStatus.ANALYZING, VoiceSessionStatus.PARTIAL_READY,
            VoiceSessionStatus.READY_TO_SAVE, VoiceSessionStatus.SAVING, VoiceSessionStatus.SAVED)
