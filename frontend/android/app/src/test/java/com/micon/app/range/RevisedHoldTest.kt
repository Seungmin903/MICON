package com.micon.app.range

import com.micon.app.data.engine.EngineClient
import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RevisedHoldTest {
    private class Driver(revised: Boolean = true) {
        val tracker = BufferedHoldTracker(60,60,.001,revised)
        var t = 0.0
        val ends = mutableListOf<BufferedHoldTracker.Record>()
        fun feed(seconds: Double, midi: Double? = 60.0, rms: Double = .1, clip: Double = 0.0) {
            val until = t + seconds
            while (t < until - 1e-8) {
                val dt = minOf(.01,until-t)
                tracker.accept(RangeFrame(t,dt,midi?.let(ScoringMath::midiToHz) ?: 0.0,
                    if(midi==null)0.0 else 1.0,rms,clippedRatio=clip))
                tracker.ended?.let(ends::add); t += dt
            }
        }
        fun hold(seconds: Double = 1.2) { feed(.2); feed(seconds) }
        fun quiet(seconds: Double) = feed(seconds,null,.0001)
    }

    @Test fun staggeredVoiceEndPreservesRecordAndEmitsOnlyOnce() {
        for (kind in listOf("together","volume_first","tracking_first")) {
            val d=Driver();d.hold()
            if(kind=="volume_first") d.feed(.1,rms=.0001)
            if(kind=="tracking_first") d.feed(.1,null)
            d.quiet(.11);assertTrue(kind,d.ends.isEmpty())
            d.quiet(.01)
            val end=d.ends.single()
            assertEquals("voice_ended",end.endReason)
            assertEquals(1.2,end.metrics.creditedHoldSeconds!!,1e-7)
            assertEquals(1.4,end.end,1e-7)
            assertEquals(end,d.tracker.best)
            d.quiet(.5);assertEquals(1,d.ends.size)
        }
    }
    @Test fun graceUsesFirstInvalidTimestampAndDoesNotExtend() {
        for(delay in listOf(.18,.19,.35)) {
            val d=Driver();d.hold();d.feed(delay,null);d.quiet(.12)
            assertEquals("delay=$delay",if(delay==.18)1 else 0,d.ends.size)
            assertEquals(1.2,d.tracker.best!!.metrics.creditedHoldSeconds!!,1e-7)
        }
    }
    @Test fun trackingLossAloneAndClippingNeverAdvance() {
        val d=Driver();d.hold();d.feed(.8,null);assertTrue(d.ends.isEmpty())
        for(clipFirst in listOf(true,false)) {
            val c=Driver();c.hold()
            if(!clipFirst)c.feed(.02,null)
            c.feed(.02,clip=.02);c.quiet(.2)
            assertTrue(c.ends.isEmpty());assertTrue(c.tracker.best!!.metrics.passed)
        }
    }
    @Test fun recoveredInputStartsNewRunAndCannotMergeTheQuietTail() {
        val d=Driver();d.hold();d.feed(.05,null);d.feed(.5);d.quiet(.12)
        val r=d.ends.single()
        assertEquals(.3,r.metrics.creditedHoldSeconds!!,1e-7)
        assertFalse(r.metrics.passed)
        assertEquals(1.2,d.tracker.best!!.metrics.creditedHoldSeconds!!,1e-7)
    }
    @Test fun replayStopAndFrameGapsCancelPendingEnd() {
        for(reason in listOf("replay","user_stopped","app_interrupted","input_quality","gap")) {
            val d=Driver();d.hold();d.feed(.03,null)
            if(reason=="gap") d.t+=.02 else d.tracker.seal(reason)
            d.quiet(.2);assertTrue(reason,d.ends.isEmpty())
            assertTrue(d.tracker.best!!.metrics.passed)
        }
    }
    @Test fun oneSecondDoesNotEndUntilReleaseOrTwoPointFiveSeconds() {
        val d=Driver();d.hold(1.0);assertTrue(d.ends.isEmpty())
        d.feed(1.49);assertTrue(d.ends.isEmpty());d.feed(.01)
        assertEquals("max_duration",d.ends.single().endReason)
        assertEquals(100.0,d.ends.single().metrics.stabilityScore!!,1e-6)
        val short=Driver();short.hold(.99);short.quiet(.12)
        assertFalse(short.ends.single().metrics.passed)
    }
    @Test fun scoreScalesBothComponentsByDurationAndKeepsLegacyFormula() {
        for(revised in listOf(false,true)) {
            val d=Driver(revised);d.hold(1.0);d.tracker.seal("manual")
            val m=d.tracker.best!!.metrics
            assertEquals(if(revised)40.0 else 70.0,m.stabilityScore!!,1e-6)
            assertEquals(if(revised)20.0 else 50.0,m.steadinessScore!!,1e-6)
            assertEquals(revised,m.referenceRmsCents!=null)
        }
    }
    @Test fun fixedReferencePenalizesDriftAndCompletedTailCannotBeDiscarded() {
        val d=Driver();d.feed(.2);d.feed(1.2,60.3)
        val live=d.tracker.snapshot("checkpoint")!!
        d.feed(.3,61.0);d.tracker.seal("manual")
        val m=d.tracker.best!!.metrics
        assertEquals(1.2,m.creditedHoldSeconds!!,1e-6)
        assertEquals(12.0,m.excursionPenalty!!,1e-6)
        assertTrue(m.referenceRmsCents!!>m.pitchSpreadCents!!)
        assertTrue(m.stabilityScore!! < live.metrics.stabilityScore!!)
        assertEquals(12.0,m.stabilityScore!!,1e-6)
    }
    @Test fun sessionAdvancesStaggeredEndAndExportsVersionedContract() {
        val s=RangeSession("v7-contract")
        var t=0.0
        fun feed(n:Int,midi:Double?=null,rms:Double=if(midi==null).0001 else .1) {
            repeat(n) { s.onFrame(RangeFrame(t,.02,midi?.let(ScoringMath::midiToHz)?:0.0,
                if(midi==null)0.0 else 1.0,rms));t+=.02 }
        }
        feed(55);feed(101,60.0);s.cueFinished(s.view().cueId,t);feed(14)
        feed(70,60.0);assertEquals(60,s.view().targetMidi)
        feed(4,60.0,.0001);feed(6)
        assertEquals(66,s.view().targetMidi)
        val a=s.view().attempts.single()
        assertEquals("threshold",a.advanceReason);assertEquals("voice_ended",a.runEndReason)
        assertEquals(1.2,a.metrics!!.creditedHoldSeconds!!,1e-6)
        assertEquals(48.0,a.metrics.stabilityScore!!,1e-6)
        val cue=s.view().cueId
        feed(20);s.cueFinished(cue-1,t);assertEquals(cue,s.view().cueId)
        s.stop("user_stopped",t)
        val submission=s.submission()
        assertEquals("guided-range-v7",submission.protocolVersion)
        val file=File("build/range-v7-contract/ended.json");file.parentFile.mkdirs()
        file.writeText(EngineClient.moshi.adapter(RangeSubmission::class.java).toJson(submission))
    }
}
