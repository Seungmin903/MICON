package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test

class RangeStabilityPlotTest {
    private fun note(midi: Int, ratio: Double, status: String = "passed") =
        RangeNote(midi, status, RangeMetrics(10.0, 5.0, ratio, 1.0, 2.0))

    @Test fun `missing semitones stay null and break the graph`() {
        val plot = RangeStabilityPlot.from(listOf(note(60, .97), note(62, .91), note(63, .6, "not_passed")))
        assertNull(plot.points.first { it.note.midi == 61 }.ratio)
        assertEquals("unmeasured", plot.points.first { it.note.midi == 61 }.note.status)
        assertEquals(listOf(62 to 63), plot.segments.map { it.first.note.midi to it.second.note.midi })
    }

    @Test fun `measured zero differs from missing or uncertain evidence`() {
        val plot = RangeStabilityPlot.from(listOf(note(60, 0.0, "not_passed"), note(61, .98, "needs_review"),
            note(62, .99, "insufficient_quality"), note(63, .96, "unmeasured")))
        assertEquals(0f, plot.points.first { it.note.midi == 60 }.ratio)
        assertTrue(plot.points.filter { it.note.midi in 61..63 }.all { it.ratio == null })
        assertTrue(plot.segments.isEmpty())
    }

    @Test fun `good latest ratio does not make an inconsistent note confirmed`() {
        val point = RangeStabilityPlot.from(listOf(note(60, 1.0, "inconsistent"))).points.first { it.note.midi == 60 }
        assertEquals(1f, point.ratio)
        assertFalse(point.confirmed)
    }

    @Test fun `empty single and full range plots preserve supported bounds`() {
        assertTrue(RangeStabilityPlot.from(emptyList()).points.all { it.ratio == null })
        for (midi in listOf(36, 84)) {
            val plot = RangeStabilityPlot.from(listOf(note(midi, .95)))
            assertEquals(1, plot.points.count { it.ratio != null })
            assertTrue(plot.points.all { it.note.midi in 36..84 })
            assertTrue(plot.segments.isEmpty())
        }
        assertEquals((36..84).toList(), RangeStabilityPlot.from(emptyList(), true).points.map { it.note.midi })
    }

    @Test fun `invalid ratios are not drawn as evidence`() {
        val plot = RangeStabilityPlot.from(listOf(note(60, Double.NaN), note(61, 1.1), note(62, -.1), note(63, Double.POSITIVE_INFINITY)))
        assertTrue(plot.points.all { it.ratio == null })
    }
}
