package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test

class RangeMapPresentationTest {
    private val metrics = RangeMetrics(5.0, 8.0, 1.0, 1.0, 2.0, stabilityScore = 90.0)
    private val report = RangeReport(RangeProtocol.VERSION, listOf(
        RangeNote(48, "passed", metrics), RangeNote(60, "reconfirmed", metrics),
        RangeNote(64, "not_passed", metrics)), 48, 60, emptyList(), "completed", "FOUND", "FOUND", "available")

    @Test fun serverNotesRecoverOverallStableAndRecommendedRanges() {
        assertEquals(RangeMap(listOf(48, 64), listOf(48, 60), listOf(50, 58)), report.continuousRangeMap())
    }

    @Test fun oldCachedOverallOnlyMapStillGetsStableAndRecommendedRows() {
        assertEquals(RangeMap(listOf(48, 60), listOf(48, 60), listOf(50, 58)),
            report.copy(rangeMap = RangeMap(overall = listOf(48, 60))).continuousRangeMap())
    }

    @Test fun missingDetailedMeasurementsDoNotInventStableOrRecommendedRange() {
        assertEquals(RangeMap(overall = listOf(48, 60)), report.copy(notes = emptyList()).continuousRangeMap())
        assertNull(report.copy(notes = listOf(RangeNote(60, "passed"))).continuousRangeMap().recommended)
    }

    @Test fun scoreBasedComfortUsesExtremeHighScoresEvenWithZeroBetweenThem() {
        val quality = VoiceQualityResult(LocalVoiceQualityAnalyzer.VERSION, 57, listOf(
            VoiceQualityPoint(55, 85.0, 0.0, 0.0, 100.0),
            VoiceQualityPoint(56, 0.0, 0.0, 0.0, 0.0),
            VoiceQualityPoint(57, 100.0, 0.0, 0.0, 100.0),
            VoiceQualityPoint(58, 90.0, 0.0, 0.0, 100.0)))
        val scored = report.copy(protocolVersion = RangeProtocol.FREE_VERSION, notes = emptyList(),
            lowMidi = 48, highMidi = 72, stableLowMidi = 50.0, stableHighMidi = 70.0,
            voiceQuality = quality)
        assertEquals(RangeMap(listOf(48, 72), listOf(55, 58), listOf(55, 58)), scored.continuousRangeMap())
        assertEquals(RangeMap(listOf(48, 72), null, null),
            scored.copy(voiceQuality = quality.copy(points = quality.points.filter { it.score < 80 })).continuousRangeMap())
    }
}
