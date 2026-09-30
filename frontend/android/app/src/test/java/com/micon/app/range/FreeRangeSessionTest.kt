package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

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
        feed(session, 1.1, 4.3, { 60.0 })
        assertEquals("transition", session.view().phase)
    }

    @Test fun eachSweepRunsItsMissionAndKeepsTheExtremeItReached() {
        val session = FreeRangeSession("free-test")
        toComfort(session)
        assertEquals(1, session.view().completed)
        assertNotNull(FreeRangeSession.restore(session.checkpoint(4.3)!!))

        assertTrue(session.advance(4.3))
        assertEquals("down", session.view().phase)
        feed(session, 4.3, 11.0, sweep(60.0, 57.0, 59.0, 4.3, 7.0, 8.0))
        assertEquals("down", session.view().phase)
        assertEquals(57.0, session.view().extremeMidi!!, .2)
        feed(session, 11.0, 14.4, sweep(60.0, 57.0, 59.0, 4.3, 7.0, 8.0))
        assertEquals("transition", session.view().phase)
        assertEquals(2, session.view().completed)

        assertTrue(session.advance(14.4))
        feed(session, 14.4, 29.5, sweep(60.0, 64.0, 62.0, 14.4, 17.1, 18.1))
        assertEquals("up", session.view().phase)
        feed(session, 29.5, 34.5, sweep(60.0, 64.0, 62.0, 14.4, 17.1, 18.1))
        assertEquals("done", session.view().phase)

        val result = session.submission(34.6)!!
        assertEquals(RangeProtocol.FREE_VERSION, result.protocolVersion)
        assertEquals(listOf("comfort", "down", "up"), result.stages.map { it.kind })
        val down = result.stages[1]
        val up = result.stages[2]
        assertEquals(FreeRangeSession.DOWN_MISSION_SECONDS, down.end - down.start, .1)
        assertEquals(FreeRangeSession.UP_MISSION_SECONDS, up.end - up.start, .1)
        assertEquals(57.0, down.endpointMidi, .2)
        assertEquals(64.0, up.endpointMidi, .2)
        assertTrue(down.holdStart - down.start >= FreeRangeSession.ENDPOINT_OFFSET_SECONDS)
    }

    /** The mission time is a floor: a late but usable segment still ends the stage. */
    @Test fun aSweepKeepsListeningUntilItHasAUsableSegment() {
        val session = FreeRangeSession("late-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        // More than ten seconds of the start note alone: nothing to record yet.
        feed(session, 4.3, 21.0, { 60.0 })
        assertEquals("down", session.view().phase)
        assertTrue(session.view().elapsed > FreeRangeSession.DOWN_MISSION_SECONDS)
        // Only now does the voice actually descend.
        feed(session, 21.0, 23.0, { t -> 60.0 - 3.0 * (t - 21.0) / 2.0 })
        feed(session, 23.0, 25.0, { 57.0 })
        assertEquals("transition", session.view().phase)
        val down = checkNotNull(session.checkpoint(25.0)).stages[1]
        assertTrue(down.end - down.start > FreeRangeSession.DOWN_MISSION_SECONDS)
        assertTrue(down.end - down.start < FreeRangeSession.MAX_STAGE_SECONDS)
        assertTrue(down.endpointMidi <= 58.0)
    }

    /** Silence before the voice starts must not eat the singing time the stage owes. */
    @Test fun aLateStartStillGetsItsFiveSecondsOfSinging() {
        val session = FreeRangeSession("late-voice-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        feed(session, 4.3, 20.0, { null }, .0001)
        assertEquals("down", session.view().phase)
        // The pitch conditions are met within two seconds, but the stage owes five of voice.
        feed(session, 20.0, 21.5, { t -> 60.0 - 3.0 * (t - 20.0) / 1.5 })
        feed(session, 21.5, 23.0, { 57.0 })
        assertEquals("down", session.view().phase)
        assertTrue(session.view().voicedSeconds < FreeRangeSession.MIN_VOICED_SECONDS)
        feed(session, 23.0, 25.5, { 57.0 })
        assertEquals("transition", session.view().phase)
        val down = checkNotNull(session.checkpoint(25.5)).stages[1]
        assertEquals(57.0, down.endpointMidi, .3)
    }

    @Test fun overtimeSilenceShowsVoiceDetectionGuidanceInsteadOfAStaleCountdown() {
        val session = FreeRangeSession("quiet-overtime-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        feed(session, 4.3, 15.0, { null }, .0001)
        assertEquals("down", session.view().phase)
        assertTrue(session.view().message.contains("목소리가 충분히 인식되지"))
        assertFalse(session.view().message.contains("초 더"))
    }

    /** A single misdetected frame is not a steady run, so it can never set the boundary. */
    @Test fun anIsolatedOctaveSpikeDoesNotBecomeTheBoundary() {
        val session = FreeRangeSession("spike-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        val shape = sweep(60.0, 57.0, 59.0, 4.3, 7.0, 8.0)
        feed(session, 4.3, 19.4, { t -> if (t > 7.5 && t < 7.54) 45.0 else shape(t) })
        assertEquals("transition", session.view().phase)
        val down = checkNotNull(session.checkpoint(19.4)).stages[1]
        assertEquals(57.0, down.endpointMidi, .2)
        assertTrue(down.holdEnd - down.holdStart >= FreeRangeSession.EXTREME_HOLD_SECONDS - .05)
    }

    @Test fun aSweepThatNeverMovesFailsOnlyAtTheHardCap() {
        val session = FreeRangeSession("flat-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        feed(session, 4.3, 30.0, { 59.5 })
        assertEquals("down", session.view().phase)
        feed(session, 30.0, 49.5, { 59.5 })
        assertEquals("failed", session.view().phase)
        assertEquals(1, session.view().completed)
    }

    @Test fun aSilentSweepFailsOnlyAtTheHardCap() {
        val session = FreeRangeSession("silent-test")
        toComfort(session)
        assertTrue(session.advance(4.3))
        feed(session, 4.3, 30.0, { null }, .0001)
        assertEquals("down", session.view().phase)
        feed(session, 30.0, 49.5, { null }, .0001)
        assertEquals("failed", session.view().phase)
        assertEquals(1, session.view().completed)
    }

    @Test fun comfortNeedsOneAndAHalfSecondsOfSteadyVoice() {
        val tooShort = FreeRangeSession("short-hold-test")
        feed(tooShort, 0.0, 1.1, { null }, .0001)
        feed(tooShort, 1.1, 2.5, { 60.0 })
        assertEquals("comfort", tooShort.view().phase)

        val long = FreeRangeSession("long-hold-test")
        feed(long, 0.0, 1.1, { null }, .0001)
        feed(long, 1.1, 2.7, { 60.0 })
        assertEquals("transition", long.view().phase)
        assertEquals(1, long.view().completed)
    }

    /** A natural vibrato stays inside the tolerance, so it still counts as one held note. */
    @Test fun comfortAcceptsVibratoWithinTheTolerance() {
        val session = FreeRangeSession("vibrato-test")
        feed(session, 0.0, 1.1, { null }, .0001)
        feed(session, 1.1, 2.8, { t -> 60.0 + sin(2 * PI * 5 * (t - 1.1)) })
        assertEquals("transition", session.view().phase)
        assertEquals(60.0, session.checkpoint(2.8)!!.stages.first().endpointMidi, .3)
    }
}
