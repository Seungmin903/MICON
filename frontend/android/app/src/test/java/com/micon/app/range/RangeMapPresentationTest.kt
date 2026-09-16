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
}
