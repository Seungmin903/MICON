package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class FreeRangeSessionTest {
    private fun frame(t: Double, midi: Double?, rms: Double = if (midi == null) .0001 else .02) =
        RangeFrame(t, .02, midi?.let { 440.0 * 2.0.pow((it - 69) / 12) } ?: 0.0,
            if (midi == null) 0.0 else .95, rms, rms)

    private fun feed(session: FreeRangeSession, start: Double, end: Double,
                     pitch: (Double) -> Double?, rms: Double = .02) {
        for (step in 0 until ((end - start) / .02).toInt()) {
            val time = start + step * .02
            session.onFrame(frame(time, pitch(time), rms))
        }
    }

    /** Descends to the extreme, holds it briefly, then drifts back — the extreme still wins. */
    private fun sweep(from: Double, extreme: Double, settle: Double, stageStart: Double,
                      rampEnd: Double, holdEnd: Double): (Double) -> Double = { t ->
        val since = t - stageStart
        when {
            since < 1.2 -> from
            t < rampEnd -> from + (extreme - from) * (t - (stageStart + 1.2)) / (rampEnd - stageStart - 1.2)
            t < holdEnd -> extreme
            else -> extreme + (settle - extreme) * (t - holdEnd) / 6.0
        }
    }

    private fun toComfort(session: FreeRangeSession) {
        feed(session, 0.0, 1.1, { null }, .0001)
        assertEquals("comfort", session.view().phase)
        feed(session, 1.1, 3.3, { 60.0 })
        assertEquals("transition", session.view().phase)
    }

    @Test fun eachSweepRunsItsFullMissionAndKeepsTheExtremeItReached() {
        val session = FreeRangeSession("free-test")
        toComfort(session)
        assertEquals(1, session.view().completed)
        val restored = FreeRangeSession.restore(session.checkpoint(3.3)!!)
        assertEquals("transition", restored?.view()?.phase)

        assertTrue(session.advance(3.3))
        assertEquals("down", session.view().phase)
        // The stage does not end when the low note stops sounding; it ends when the clock does.
        feed(session, 3.3, 10.0, sweep(60.0, 57.0, 59.0, 3.3, 6.0, 7.0))
        assertEquals("down", session.view().phase)
        assertEquals(57.0, session.view().extremeMidi!!, .2)
        assertTrue(session.view().remainingSeconds > 8.0)
        feed(session, 10.0, 18.4, sweep(60.0, 57.0, 59.0, 3.3, 6.0, 7.0))
        assertEquals("transition", session.view().phase)
        assertEquals(2, session.view().completed)

        assertTrue(session.advance(18.4))
        feed(session, 18.4, 33.5, sweep(60.0, 64.0, 62.0, 18.4, 21.1, 22.1))
        assertEquals("done", session.view().phase)

        val result = session.submission(33.6)!!
        assertEquals(RangeProtocol.FREE_VERSION, result.protocolVersion)
        assertEquals(listOf("comfort", "down", "up"), result.stages.map { it.kind })
        assertTrue(result.attempts.isEmpty())
        val down = result.stages[1]
        val up = result.stages[2]
        assertEquals(FreeRangeSession.MISSION_SECONDS, down.end - down.start, .1)
        assertEquals(FreeRangeSession.MISSION_SECONDS, up.end - up.start, .1)
        assertEquals(57.0, down.endpointMidi, .2)
        assertEquals(64.0, up.endpointMidi, .2)
        assertTrue(down.holdEnd - down.holdStart >= FreeRangeSession.EXTREME_HOLD_SECONDS - .05)
        assertTrue(down.holdStart - down.start >= FreeRangeSession.ENDPOINT_OFFSET_SECONDS)
        assertNotNull(FreeRangeSession.restore(session.checkpoint(33.6)!!)?.submission(33.6))
    }

    /** A single misdetected frame is not a steady run, so it can never set the boundary. */
    @Test fun anIsolatedOctaveSpikeDoesNotBecomeTheBoundary() {
        val session = FreeRangeSession("spike-test")
        toComfort(session)
        assertTrue(session.advance(3.3))
        val shape = sweep(60.0, 57.0, 59.0, 3.3, 6.0, 7.0)
        feed(session, 3.3, 18.4, { t -> if (t > 6.5 && t < 6.54) 45.0 else shape(t) })
        assertEquals("transition", session.view().phase)
        val down = checkNotNull(session.checkpoint(18.4)).stages[1]
        assertEquals(57.0, down.endpointMidi, .2)
        assertTrue(down.holdEnd - down.holdStart >= FreeRangeSession.EXTREME_HOLD_SECONDS - .05)
    }

    @Test fun aSweepThatNeverMovesTwoSemitonesFails() {
        val session = FreeRangeSession("flat-test")
        toComfort(session)
        assertTrue(session.advance(3.3))
        feed(session, 3.3, 18.4, { 59.5 })
        assertEquals("failed", session.view().phase)
        assertEquals(1, session.view().completed)
        assertTrue(session.retryCurrent(18.4))
        assertEquals("down", session.view().phase)
        assertEquals(1, session.view().completed)
    }

    @Test fun failedStageRetriesWithoutDiscardingPreviousStages() {
        val session = FreeRangeSession("retry-test")
        toComfort(session)
        assertTrue(session.advance(3.3))
        feed(session, 3.3, 18.5, { null }, .0001)
        assertEquals("failed", session.view().phase)
        assertEquals(1, session.view().completed)
        assertTrue(session.retryCurrent(18.5))
        assertEquals("down", session.view().phase)
        assertEquals(1, session.view().completed)
    }

    @Test fun comfortStillTakesTheMedianOfAHalfSecondHoldWithinOneAndHalfSemitones() {
        val session = FreeRangeSession("short-hold-test")
        feed(session, 0.0, 1.1, { null }, .0001)
        feed(session, 1.1, 1.58, { time ->
            if (time < 1.12) 60.0 else if ((time * 50).toInt() % 2 == 0) 58.6 else 61.4
        })
        assertEquals("comfort", session.view().phase)
        feed(session, 1.58, 1.62, { 60.0 })
        assertEquals("transition", session.view().phase)
        assertEquals(1, session.view().completed)
    }
}
