package com.micon.app.range

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.audio.ReferenceTone
import com.micon.app.scoring.YinPitch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises the device OGG decoder and AudioTrack, without recording or saving a profile. */
@RunWith(AndroidJUnit4::class)
class PianoReferenceTest {
    @Test fun decodedPianoCoversLowAndHighTargetsAndPlaybackCanBeInterrupted() = runBlocking {
        val tone = ReferenceTone(InstrumentationRegistry.getInstrumentation().targetContext)
        tone.prepare()
        for (midi in listOf(36, 47, 60, 84)) {
            val samples = tone.pcm(midi, 1.0)
            assertEquals(ReferenceTone.RATE, samples.size)
            assertTrue(samples.any { abs(it.toInt()) > 500 })
            val pitches = listOf(.12, .22, .32).map { t ->
                val start = (t * ReferenceTone.RATE).toInt()
                val frame = FloatArray(2048) { i -> samples[start + i] / 32768f }
                RangeProtocol.midi(YinPitch.yinPitch(frame, ReferenceTone.RATE.toDouble(), 60.0, 1150.0).f0)
            }
            assertTrue("$midi: $pitches", abs(RangeEvaluator.median(pitches)!! - midi) * 100 < 35)
            assertEquals(0, samples.last().toInt())
        }
        val start = SystemClock.elapsedRealtime()
        tone.play(36, .5)
        assertTrue(SystemClock.elapsedRealtime() - start >= 450)
        val playing = launch { tone.play(60, 1.0) }
        delay(80)
        playing.cancelAndJoin()
        // A fresh cue must work after cancellation released the previous AudioTrack.
        tone.play(84, .5)
    }
}
