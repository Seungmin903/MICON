package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test

class DeviceRangeReportTest {
    private fun submission(reason: String = "completed"): RangeSubmission {
        fun attempt(id: String, midi: Int, status: String) = RangeAttempt(id, midi, "ascending",
            0.0, 1.0, 1.0, 4.0, 1.0, 3.5, status,
            RangeMetrics(5.0, 8.0, 1.0, 1.0, 2.5, creditedMidi = midi,
                stabilityScore = 90.0, creditedHoldSeconds = 2.5))
        return RangeSubmission("session", startMidi = 60, noiseRms = .01, durationSeconds = 12.0,
            stopReason = reason, lowerBoundaryStatus = "measured", upperBoundaryStatus = "measured",
            attempts = listOf(attempt("low", 48, "passed"), attempt("high", 72, "passed"),
                attempt("failed", 78, "not_passed")), registerEvents = emptyList())
    }

    private fun freeRangeSubmission(quality: VoiceQualityResult? = null) = RangeSubmission(
        sessionId = "free-session", protocolVersion = RangeProtocol.FREE_VERSION,
        startMidi = 60, noiseRms = .01, durationSeconds = 33.5,
        stopReason = "completed", lowerBoundaryStatus = "measured", upperBoundaryStatus = "measured",
        attempts = emptyList(), registerEvents = emptyList(),
        stages = listOf(
            FreeRangeStage("comfort", 0.0, 3.0, 60.0, 60.0, 1.0, 2.6),
            FreeRangeStage("down", 3.2, 13.2, 60.0, 57.0, 7.0, 7.5),
            FreeRangeStage("up", 13.4, 33.4, 60.0, 64.0, 20.0, 20.5),
        ), voiceQuality = quality,
    )

    private fun qualityPoint(midi: Int) = VoiceQualityPoint(
        midi = midi, score = 90.0, highChange = 0.0,
        lowIrregularity = 0.0, holdScore = 90.0,
    )

    @Test fun measuredExtremaHaveNoArtificialSemitonePadding() {
        val report = submission().deviceReport()
        assertEquals(48, report.lowMidi)
        assertEquals(72, report.highMidi)
        assertEquals("device_measured", report.verification)
        assertEquals(listOf(48, 72), report.rangeMap?.overall)
        assertNull(report.rangeMap?.recommended)
        assertNull(report.notes.find { it.midi == 55 }?.metrics)
    }

    @Test fun interruptedOrFaultyInputCannotAdvanceOnboarding() {
        for (reason in listOf("app_interrupted", "input_quality")) {
            assertEquals("unavailable", submission(reason).deviceReport().availability)
        }
    }

    @Test fun emptyMeasurementHasNoInventedRange() {
        val report = submission().copy(attempts = emptyList()).deviceReport()
        assertNull(report.lowMidi)
        assertNull(report.highMidi)
        assertEquals("unavailable", report.availability)
    }

    @Test fun onboardingPreviewRequiresTheExistingProfileGateAndNormalCompletion() {
        val complete = usableRangeSubmission()
        assertNotNull(complete.onboardingPreview())
        assertEquals("device_measured", complete.onboardingPreview()!!.verification)
        assertNull(complete.copy(sampling = RangeSampling(measuredNoteCount = 2)).onboardingPreview())
        assertNull(complete.copy(sampling = RangeSampling(basicCompleted = true)).onboardingPreview())
        assertNull(complete.copy(attempts = emptyList()).onboardingPreview())
        for (reason in listOf("app_interrupted", "input_quality", "anchor_not_matched")) {
            assertNull(complete.copy(stopReason = reason).onboardingPreview())
        }
    }

    @Test fun freeRangePreviewCannotAdvanceWithoutVoiceQuality() {
        val freeRange = freeRangeSubmission()
        assertNull(RangeInputQuality.metadataProblem(freeRange))
        assertNull(freeRange.onboardingPreview())
    }

    @Test fun freeRangePreviewCannotAdvanceWithoutMeasuredReferencePoint() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 60, listOf(qualityPoint(57)))
        val freeRange = freeRangeSubmission(quality)
        assertNull(RangeInputQuality.metadataProblem(freeRange))
        assertNull(freeRange.onboardingPreview())
    }

    @Test fun freeRangePreviewAdvancesWithMeasuredReferencePoint() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 60,
            listOf(qualityPoint(57), qualityPoint(60), qualityPoint(64)))
        val freeRange = freeRangeSubmission(quality)
        assertNull(RangeInputQuality.metadataProblem(freeRange))

        val preview = freeRange.onboardingPreview()
        assertNotNull(preview)
        assertEquals("available", preview!!.availability)
        assertEquals("device_measured", preview.verification)
        assertEquals(quality, preview.voiceQuality)
    }

    @Test fun freeRangePreviewRejectsUnknownVersionOrWrongComfortReference() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 60, listOf(qualityPoint(60)))
        assertNull(freeRangeSubmission(quality.copy(version = "unknown")).onboardingPreview())
        assertNull(freeRangeSubmission(quality.copy(referenceMidi = 61,
            points = listOf(qualityPoint(61)))).onboardingPreview())
    }
}
