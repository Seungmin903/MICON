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

    @Test fun threeStagesRequireHeldEndpointsAndNeverSpecifyTargetNotes() {
        val session = FreeRangeSession("free-test")
        feed(session, 0.0, 1.1, { null }, .0001)
        assertEquals("comfort", session.view().phase)
        feed(session, 1.1, 3.3, { 60.0 })
        assertEquals("transition", session.view().phase)
        assertEquals(1, session.view().completed)
        val restored = FreeRangeSession.restore(session.checkpoint(3.3)!!)
        assertEquals("transition", restored?.view()?.phase)
        assertEquals(1, restored?.view()?.completed)

        assertTrue(session.advance(3.3))
        feed(session, 3.3, 4.5, { t -> 60.0 - 3.0 * (t - 3.3) / 1.2 })
        feed(session, 4.5, 6.7, { 57.0 })
        assertTrue(session.view().canFinish)
        // A noisy invalid frame is not the five seconds of silence requested by the user.
        feed(session, 6.7, 11.7, { null }, .02)
        assertEquals("down", session.view().phase)
        feed(session, 11.7, 16.9, { null }, .0001)
        assertEquals("transition", session.view().phase)
        assertEquals(2, session.view().completed)

        assertTrue(session.advance(16.9))
        feed(session, 16.9, 18.1, { t -> 60.0 + 4.0 * (t - 16.9) / 1.2 })
        feed(session, 18.1, 20.3, { 64.0 })
        assertTrue(session.finishCurrent(20.3))
        val result = session.submission(20.3)!!
        assertEquals(RangeProtocol.FREE_VERSION, result.protocolVersion)
        assertEquals(listOf("comfort", "down", "up"), result.stages.map { it.kind })
        assertTrue(result.attempts.isEmpty())
        assertNotNull(FreeRangeSession.restore(session.checkpoint(20.3)!!)?.submission(20.3))
    }

    @Test fun failedStageRetriesWithoutDiscardingPreviousStages() {
        val session = FreeRangeSession("retry-test")
        feed(session, 0.0, 1.1, { null }, .0001)
        feed(session, 1.1, 3.3, { 60.0 })
        session.advance(3.3)
        feed(session, 3.3, 23.5, { null }, .0001)
        assertEquals("failed", session.view().phase)
        assertEquals(1, session.view().completed)
        assertTrue(session.retryCurrent(23.5))
        assertEquals("down", session.view().phase)
        assertEquals(1, session.view().completed)
    }

    @Test fun halfSecondHoldUsesMedianAndAllowsOneAndHalfSemitones() {
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
