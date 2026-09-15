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
}
