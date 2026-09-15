package com.micon.app.range

import com.micon.app.scoring.YinPitch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Optional local regression: the user's original WAV is never copied into the repository. */
class DiagnosticPitchReplayTest {
    private fun audio(): FloatArray {
        val path=System.getenv("RANGE_DIAGNOSTIC_WAV")
        assumeTrue("Retained diagnostic audio supplied explicitly",path!=null && File(path).isFile)
        val b=ByteBuffer.wrap(File(path!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000,b.getInt(24));assertEquals(16,b.getShort(34).toInt())
        return FloatArray((b.limit()-44)/2) {b.getShort(44+it*2)/32768f}
    }
    @Test fun capturedF4HarmonicCorrectionNoLongerCreatesANineteenSemitoneJump() {
        val pcm=audio();val selector=GuidedPitchSelector();var corrected=0
        for(i in 0..65) {
            val t=63.592+i*.02;val center=(t*16000).roundToInt()
            val window=pcm.copyOfRange(center-512,center+512)
            val c=YinPitch.candidates(window,16000.0,60.0,1150.0)
            val f=RangeFrame(t,.02,c.corrected.f0,c.corrected.clarity,YinPitch.frameRms(window),
                min(YinPitch.frameRms(window,0,512),YinPitch.frameRms(window,512,1024)))
            val chosen=selector.select(f,c.initial.f0,c.initial.clarity,.001,"f4")
            if(t >=64.232-1e-8 && t<=64.312+1e-8) {
                assertTrue(RangeProtocol.midi(c.corrected.f0)<50)
                assertEquals("initial_continuity",chosen.reason)
                assertTrue(RangeProtocol.midi(chosen.frame.f0) in 64.5..65.5);corrected++
            }
        }
        assertEquals(5,corrected)
    }
    @Test fun capturedInitialVoiceIsRejectedAsNoiseCalibration() {
        val pcm=audio();val session=RangeSession("recorded-noise")
        for(i in 0..48) {
            val center=512+i*320;val window=pcm.copyOfRange(center-512,center+512)
            val c=YinPitch.yinPitch(window,16000.0,60.0,1150.0)
            session.onFrame(RangeFrame(center/16000.0,.02,c.f0,c.clarity,YinPitch.frameRms(window),
                min(YinPitch.frameRms(window,0,512),YinPitch.frameRms(window,512,1024))))
        }
        assertEquals("noise",session.view().phase)
        assertEquals(.001,session.view().rmsFloor,1e-9)
    }
    @Test fun realOctaveChangesAndStrongHarmonicLowNotesAreNotPinned() {
        val selector=GuidedPitchSelector()
        val observed=mutableListOf<Double>()
        for(i in 0..9) {
            val hz=if(i<5)130.8128 else 261.6256
            val signal=FloatArray(1024) {s->(.05*sin(2*PI*hz*s/16000)+.25*sin(4*PI*hz*s/16000)+.15*sin(6*PI*hz*s/16000)).toFloat()}
            val c=YinPitch.candidates(signal,16000.0,60.0,1150.0)
            val chosen=selector.select(RangeFrame(i*.02,.02,c.corrected.f0,c.corrected.clarity,.1),c.initial.f0,c.initial.clarity,.001,"same")
            observed.add(RangeProtocol.midi(chosen.frame.f0))
        }
        assertTrue(observed.take(5).all{abs(it-48)<.15})
        assertTrue(observed.drop(5).all{abs(it-60)<.15})
    }
    @Test fun shortF3AndF4DriftPreservesCreditedRunAndA3LowVolumeIsUncertain() {
        val pcm=audio()
        for((target,span) in listOf(53 to (40.832 to 41.492),65 to (64.612 to 65.872),57 to (24.152 to 24.912))) {
            val tracker=BufferedHoldTracker(target,57,.03706607567924467)
            val selector=GuidedPitchSelector()
            var t=span.first
            var before: BufferedHoldTracker.Record? = null
            while(t <= span.second+1e-8) {
                val center=(t*16000).roundToInt();val window=pcm.copyOfRange(center-512,center+512)
                val c=YinPitch.candidates(window,16000.0,60.0,1150.0)
                val f=RangeFrame(t,.02,c.corrected.f0,c.corrected.clarity,YinPitch.frameRms(window),
                    min(YinPitch.frameRms(window,0,512),YinPitch.frameRms(window,512,1024)))
                val chosen=selector.select(f,c.initial.f0,c.initial.clarity,.03706607567924467,"${tracker.generation}")
                before=tracker.current;tracker.accept(chosen.frame);t+=.02
            }
            if(target==57) {
                assertEquals("input_uncertain",tracker.lastDecision)
                assertNull(tracker.ended)
                assertNotNull(tracker.best)
            } else {
                assertNull(tracker.ended)
                assertNotNull(tracker.current)
                assertNotNull(before)
                assertEquals(before!!.start,tracker.current!!.start,1e-8)
                assertTrue(tracker.current!!.metrics.creditedHoldSeconds!! >= before!!.metrics.creditedHoldSeconds!! - 1e-8)
                assertTrue(tracker.zone in setOf("normal","buffer"))
            }
        }
    }
}
