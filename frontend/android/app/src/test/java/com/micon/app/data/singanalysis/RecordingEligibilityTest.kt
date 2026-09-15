package com.micon.app.data.singanalysis

import org.junit.Assert.*
import org.junit.Test

class RecordingEligibilityTest {
    private fun record(timer: RecordingEligibility, duration: Double) {
        for (step in 0..(duration * 100).toInt()) {
            val t = step / 100.0
            timer.sample(t, t + 20, t, true)
        }
    }

    @Test fun allFourFinishCombinations() {
        val short = RecordingEligibility().also { record(it, 29.99) }
        assertFalse(short.canFinish(false))
        assertFalse(short.canFinish(true))
        val enough = RecordingEligibility().also { record(it, 30.1) }
        assertFalse(enough.canFinish(false))
        assertTrue(enough.canFinish(true))
    }

    @Test fun pauseAndBufferingEarnNoTime() {
        val timer = RecordingEligibility()
        record(timer, 10.0)
        val before = timer.seconds
        timer.pause()
        for (i in 0..100) timer.sample(10.0 + i, 30.0, 10.0 + i, false)
        assertEquals(before, timer.seconds, 0.0001)
        timer.sample(110.0, 30.0, 110.0, true)
        timer.sample(110.1, 30.1, 110.1, true)
        assertEquals(before + 0.1, timer.seconds, 0.0001)
    }

    @Test fun seekOrMicStallDoesNotCount() {
        val timer = RecordingEligibility()
        timer.sample(0.0, 0.0, 0.0, true)
        timer.sample(0.1, 40.0, 0.1, true)
        timer.sample(0.1, 40.1, 0.2, true)
        timer.sample(0.2, 10.0, 0.3, true)
        assertEquals(0.0, timer.seconds, 0.0)
    }

    @Test fun micErrorInvalidatesEvenLongRecording() {
        val timer = RecordingEligibility().also { record(it, 31.0) }
        timer.invalidate()
        assertFalse(timer.canFinish(true))
    }

    @Test fun audioBlocksDoNotLoseUncreditedTimeBetweenUiTicks() {
        val timer = RecordingEligibility()
        for (i in 0..1000) {
            val wall = i * 0.033
            val audio = kotlin.math.floor(wall / 0.064) * 0.064
            timer.sample(audio, wall, wall, true)
        }
        assertEquals(33.0, timer.seconds, 0.065)
        assertTrue(timer.canFinish(true))
    }

    @Test fun segmentsContainOnlyConcurrentProgress() {
        val timer = RecordingEligibility().also { record(it, 2.0) }
        val length = timer.takes.sumOf { it.wavEndSec - it.wavStartSec }
        assertEquals(timer.seconds, length, 0.0001)
        assertTrue(timer.takes.all { it.wavEndSec > it.wavStartSec && it.songEndSec > it.songStartSec })
    }
}
