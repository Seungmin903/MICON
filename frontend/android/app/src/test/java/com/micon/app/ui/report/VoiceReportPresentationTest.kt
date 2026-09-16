package com.micon.app.ui.report

import com.micon.app.core.network.Json
import com.micon.app.data.onboarding.*
import com.micon.app.range.*
import org.junit.Assert.*
import org.junit.Test

class VoiceReportPresentationTest {
    private val range = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(60, "passed")),
        60, 72, emptyList(), "completed", "FOUND", "FOUND", "available", backendSessionId = 7)
    private val complete = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
        byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.RUNNING,
        rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.PARTIAL_READY)

    @Test fun bothTimbreMeasurementsMustFinishBeforeResultsAreRevealed() {
        for (status in ComponentStatus.entries.filter { it != ComponentStatus.COMPLETED }) {
            assertFalse(complete.copy(ecapa = status).hasTimbreReport(range))
            assertFalse(complete.copy(byol = status).hasTimbreReport(range))
        }
        assertTrue(complete.hasTimbreReport(range))
    }

    @Test fun artistSearchAndSavePermissionDoNotHideCompletedMeasurements() {
        for (status in ComponentStatus.entries) {
            assertTrue(complete.copy(artistSearch = status, saveAllowed = false).hasTimbreReport(range))
        }
        assertTrue(complete.copy(sessionStatus = VoiceSessionStatus.SAVED).hasTimbreReport(range))
    }

    @Test fun otherSessionsAndInvalidRangeNeverUnlockResults() {
        assertFalse(complete.copy(sessionId = "8").hasTimbreReport(range))
        assertFalse(complete.copy(rangeStatus = "FAILED").hasTimbreReport(range))
        assertFalse(complete.hasTimbreReport(range.copy(availability = "insufficient_data")))
        for (status in listOf(VoiceSessionStatus.CANCELED, VoiceSessionStatus.FAILED, VoiceSessionStatus.EXPIRED)) {
            assertFalse(complete.copy(sessionStatus = status).hasTimbreReport(range))
        }
    }

    @Test fun savedProfileKeepsReportAfterSerializationAndExpiryDate() {
        val profile = SavedVoiceOnboardingProfile(ProfileSaveReceipt("7", 3, "9"), range,
            complete.copy(sessionStatus = VoiceSessionStatus.SAVED, expiresAtMillis = 1, saveAllowed = false))
        val adapter = Json.moshi.adapter(SavedVoiceOnboardingProfile::class.java)
        val restored = checkNotNull(adapter.fromJson(adapter.toJson(profile)))
        assertTrue(restored.progress.hasTimbreReport(restored.range))
        assertFalse(restored.progress.canSave)
    }
}
