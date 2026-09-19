package com.micon.app.range

import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test

class RangePreparationWaitTest {
    private class Driver(wait: Boolean = true) {
        val session = RangeSession("preparation", awaitAnchorConfirmation = true,
            awaitComfortableConfirmation = wait)
        var time = 0.0
        fun feed(count: Int, midi: Int? = null) = repeat(count) {
            session.onFrame(RangeFrame(time, .02, midi?.let { ScoringMath.midiToHz(it.toDouble()) } ?: 0.0,
                if (midi == null) 0.0 else 1.0, if (midi == null) .0001 else .1))
            time += .02
        }
    }

    @Test fun quietCalibrationDoesNotEvaluatePitchOrAdvanceTheClockBeforeStart() {
        val d = Driver()
        d.feed(55)
        val ready = d.session.view()
        assertEquals("comfortable_ready", ready.phase)
        assertTrue(ready.inputSuppressed)
        assertNull(ready.cueMidi)
        d.feed(200, 60)
        assertEquals(ready.elapsed, d.session.view().elapsed, 0.0)
        assertNull(d.session.submission().startMidi)
        assertTrue(d.session.startComfortableMeasurement(d.time))
        assertFalse(d.session.startComfortableMeasurement(d.time))
        d.feed(101, 60)
        assertEquals("anchor_ready", d.session.view().phase)
        assertNull(d.session.view().cueMidi)
        assertEquals(0, d.session.view().cueId)
    }

    @Test fun voicedInputDoesNotMakeTheStartButtonReady() {
        val d = Driver()
        assertFalse(d.session.startComfortableMeasurement(0.0))
        d.feed(200, 60)
        assertEquals("noise", d.session.view().phase)
        d.feed(55)
        assertEquals("comfortable_ready", d.session.view().phase)
    }

    @Test fun standaloneCalibrationStillStartsComfortableMeasurementAutomatically() {
        val d = Driver(wait = false)
        d.feed(55)
        assertEquals("comfortable", d.session.view().phase)
        assertFalse(d.session.startComfortableMeasurement(d.time))
    }
}
