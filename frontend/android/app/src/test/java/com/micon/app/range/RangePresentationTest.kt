package com.micon.app.range

import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test

class RangePresentationTest {
    @Test fun `median hides isolated spikes but raw evaluation still fails repeated deviations`() {
        val display = PitchDisplayFilter()
        val frames = (0 until 100).map { i ->
            val midi = if (i % 5 == 4) 61.0 else 60.0
            val shown = display.update(midi, i * .02, 1, 60, false)
            if (i < 2) assertNull(shown) else assertEquals(60.0, shown!!, .001)
            RangeFrame(i * .02, .02, ScoringMath.midiToHz(midi), 1.0, .1)
        }
        val result = RangeEvaluator.evaluate(frames, 60, 0.0, 2.0, .001)
        assertFalse(result.passed)
        assertEquals(.8, result.inTuneRatio, .001)
    }

    @Test fun `missing input drains history and cue target and time changes reset it`() {
        val display = PitchDisplayFilter()
        repeat(5) { display.update(60.0, it * .02, 1, 60, false) }
        assertEquals(60.0, display.update(null, .1, 1, 60, false)!!, .001)
        assertNotNull(display.update(null, .12, 1, 60, false))
        assertNull(display.update(null, .14, 1, 60, false))
        assertNull(display.update(60.0, .16, 2, 60, false))
        assertNull(display.update(60.0, .18, 2, 60, false))
        assertNotNull(display.update(60.0, .20, 2, 60, false))
        assertNull(display.update(62.0, .22, 2, 62, false))
        repeat(3) { display.update(62.0, .24 + it * .02, 2, 62, false) }
        assertNull(display.update(62.0, .30, 2, 62, true))
        assertNull(display.update(62.0, .32, 2, 62, false))
        assertNull(display.update(62.0, 0.0, 2, 62, false))
        assertNull(display.update(62.0, 1.0, 2, 62, false))
    }

    private fun attempt(midi: Int, status: String) = RangeAttempt("$midi-$status", midi, "up",
        0.0, .5, .75, 3.0, null, null, status, null)

    @Test fun `full keyboard keeps unmeasured holes and prior extension outcomes`() {
        val prior = listOf(RangeNote(48, "passed", successfulAttempts = 1, attempts = 1),
            RangeNote(60, "needs_review", successfulAttempts = 0, attempts = 1))
        val notes = RangeKeyboardState.notes(prior, listOf(attempt(48, "passed"),
            attempt(50, "not_passed"), attempt(51, "insufficient_quality"),
            attempt(52, "interrupted"), attempt(60, "passed"), attempt(84, "passed")))
        assertEquals((36..84).toList(), notes.map { it.midi })
        fun note(midi: Int) = notes.single { it.midi == midi }
        assertEquals("reconfirmed", note(48).status)
        assertEquals("unmeasured", note(49).status)
        assertEquals("not_passed", note(50).status)
        assertEquals("insufficient_quality", note(51).status)
        assertEquals("unmeasured", note(52).status)
        assertEquals("needs_review", note(60).status)
        assertEquals("passed", note(84).status)
        val retest = RangeKeyboardState.notes(notes, listOf(attempt(48, "not_passed")))
        assertEquals("inconsistent", retest.single { it.midi == 48 }.status)
    }
}
