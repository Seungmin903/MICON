package com.micon.app.range

import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test

class BufferedHoldTest {
    data class Fixture(val name: String, val segments: List<List<Double>>, val T: Double, val H: Double,
                       val B: Double, val O: Double, val penalty: Double, val passed: Boolean)
    @Test fun sameRawFrameFixturesAsIndependentServer() {
        val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, Fixture::class.java)
        val cases = com.micon.app.data.engine.EngineClient.moshi.adapter<List<Fixture>>(type).fromJson(
            javaClass.getResourceAsStream("/guided-range-v5.json")!!.bufferedReader().readText())!!
        for(c in cases) {
            val d=Driver();d.seed();c.segments.forEach { d.voice(it[0],60+it[1]/100) };d.tracker.seal("manual")
            val m=d.tracker.best!!.metrics
            assertEquals(c.name,c.T,m.creditedHoldSeconds!!,1e-7)
            assertEquals(c.name,c.H,m.continuousHoldSeconds,1e-7)
            assertEquals(c.name,c.B,m.bufferSeconds!!,1e-7)
            assertEquals(c.name,c.O,m.outsideSeconds!!,1e-7)
            assertEquals(c.name,c.penalty,m.excursionPenalty!!,1e-7)
            assertEquals(c.name,c.passed,m.passed)
        }
    }
    private class Driver(val tracker: BufferedHoldTracker = BufferedHoldTracker(60,60,.001)) {
        var t = 0.0
        fun voice(seconds: Double, midi: Double? = 60.0, clarity: Double = 1.0, rms: Double = .1, clip: Double = 0.0) {
            val end = t + seconds
            while (t < end - 1e-8) {
                val dt = minOf(.01,end-t)
                tracker.accept(RangeFrame(t,dt,midi?.let(ScoringMath::midiToHz) ?: 0.0,
                    if(midi == null) 0.0 else clarity,rms,clippedRatio=clip)); t += dt
            }
        }
        fun seed() = voice(.2)
    }
    @Test fun referenceUsesMedianAndAcquisitionDoesNotCount() {
        val d=Driver(); d.voice(.01,60.9); d.voice(.19)
        assertEquals(60.0,d.tracker.referenceMidi!!,1e-7)
        assertNull(d.tracker.current)
        d.voice(1.0)
        assertEquals(1.0,d.tracker.current!!.metrics.creditedHoldSeconds!!,1e-7)
        assertNull(d.tracker.ended)
    }
    @Test fun coreAndBufferBoundariesAreInclusive() {
        for(cents in listOf(45.0,45.001,100.0,100.001)) {
            val d=Driver();d.seed();d.voice(.8);d.voice(.08,60+cents/100)
            val m=d.tracker.current!!.metrics
            assertEquals(if(cents<=45) .88 else .8,m.creditedHoldSeconds!!,1e-7)
            assertEquals(if(cents>45 && cents<=100) .08 else 0.0,m.bufferSeconds!!,1e-7)
            assertEquals(if(cents>100) .08 else 0.0,m.outsideSeconds!!,1e-7)
        }
    }
    @Test fun stabilizationRequiresFifteenHundredthsOfValidEvidence() {
        for(gap in listOf(.04,.06)) {
            val d=Driver();d.voice(.08);d.voice(gap,null,rms=.0001);d.voice(.12-gap)
            if(gap==.04) assertEquals(60.0,d.tracker.referenceMidi!!,1e-7)
            else assertNull(d.tracker.referenceMidi)
        }
    }
    @Test fun bufferPausesAndResumesWithoutCreditingTheGap() {
        val d=Driver();d.seed();d.voice(.8);d.voice(.3,60.7)
        assertFalse(d.tracker.current!!.metrics.passed)
        d.voice(.3)
        val m=d.tracker.current!!.metrics
        assertEquals(1.1,m.creditedHoldSeconds!!,1e-7)
        assertEquals(.8,m.continuousHoldSeconds,1e-7)
        assertEquals(12.0,m.excursionPenalty!!,1e-7)
        assertTrue(m.passed)
    }
    @Test fun halfSecondBufferEndsOnlyAtThresholdAndIncludesPenaltyTail() {
        val d=Driver();d.seed();d.voice(1.0)
        val prefix=d.tracker.snapshot("app_interrupted")!!
        d.voice(.49,60.7)
        assertNull(d.tracker.best); assertNull(d.tracker.ended)
        d.voice(.01,60.7)
        val r=d.tracker.ended!!
        assertEquals("buffer_timeout",r.endReason)
        assertEquals(1.0,r.metrics.creditedHoldSeconds!!,1e-7)
        assertEquals(.5,r.metrics.bufferSeconds!!,1e-7)
        assertTrue(r.metrics.stabilityScore!! < prefix.metrics.stabilityScore!!)
        assertEquals(r,d.tracker.best)
    }
    @Test fun normalReturnResetsExcursionTimersButNotPenalty() {
        val d=Driver();d.seed();d.voice(.4);d.voice(.49,60.7);d.voice(.4);d.voice(.49,60.7);d.voice(.2)
        assertNotNull(d.tracker.current)
        assertEquals(.98,d.tracker.current!!.metrics.bufferSeconds!!,1e-7)
        assertEquals(20.0,d.tracker.current!!.metrics.excursionPenalty!!,1e-7)
    }
    @Test fun outsideEndsAtOneTenthAndShortExcursionCostsDouble() {
        val d=Driver();d.seed();d.voice(1.0);d.voice(.04,62.0);d.voice(.1)
        assertEquals(3.2,d.tracker.current!!.metrics.excursionPenalty!!,1e-7)
        d.voice(.09,62.0);assertNull(d.tracker.ended)
        d.voice(.01,62.0);assertEquals("outside_timeout",d.tracker.ended!!.endReason)
    }
    @Test fun fullDurationIsElapsedTimeAndShortCreditedTimeDoesNotPass() {
        val d=Driver();d.seed()
        repeat(5) { d.voice(.1);d.voice(.4,60.6) }
        val r=d.tracker.best!!
        assertEquals(2.5,r.end-r.start,1e-7)
        assertEquals(.5,r.metrics.creditedHoldSeconds!!,1e-7)
        assertFalse(r.metrics.passed)
    }
    @Test fun uncertaintyAndClippingPreserveButNeverAdvance() {
        for(kind in listOf("confidence","volume","clip","missing")) {
            val d=Driver();d.seed();d.voice(1.1)
            when(kind) {
                "confidence"->d.voice(.02,clarity=.79)
                "volume"->d.voice(.02,rms=.0009)
                "clip"->d.voice(.02,clip=.02)
                else->{d.t+=.02;d.voice(.01)}
            }
            assertTrue(kind,d.tracker.best!!.metrics.passed)
            assertNull(kind,d.tracker.ended)
            d.voice(.3)
            assertTrue(kind,(d.tracker.current?.metrics?.creditedHoldSeconds ?: 0.0)<.3)
        }
    }
    @Test fun voiceEndNeedsBothSignalsForTwelveHundredthsAndExcludesWait() {
        val d=Driver();d.seed();d.voice(1.1);d.voice(.11,null,rms=.0001)
        assertNull(d.tracker.ended)
        d.voice(.01,null,rms=.0001)
        val r=d.tracker.ended!!
        assertEquals("voice_ended",r.endReason)
        assertEquals(1.3,r.end,1e-7)
        assertEquals(r,d.tracker.best)
    }
    @Test fun tiesKeepEarlierCompletedRunAndReplayDoesNotEraseIt() {
        val d=Driver();d.seed();d.voice(.8);d.tracker.seal("replay")
        val first=d.tracker.best!!
        d.seed();d.voice(.8);d.voice(.3,60.7);d.tracker.seal("manual")
        assertEquals(first,d.tracker.best)
    }
    @Test fun targetGateIsOnlyForStartAndAttributionNeverFoldsOctaves() {
        val d=Driver();d.voice(.2,60.9);d.voice(1.0,61.3);d.tracker.seal("manual")
        assertTrue(d.tracker.best!!.metrics.passed)
        assertEquals(61,d.tracker.best!!.metrics.creditedMidi)
        val outside=Driver();outside.voice(3.0,72.0);assertNull(outside.tracker.best)
        assertNull(BufferedHoldTracker.credit(85.0,84,60))
    }
    @Test fun sessionWaitsForConfirmedEndAndReplaysKeepPenalty() {
        val s=RangeSession("v5", protocolVersion="guided-range-v5");var t=0.0
        fun voice(n:Int,midi:Double?=null) {repeat(n) {s.onFrame(RangeFrame(t,.02,midi?.let(ScoringMath::midiToHz)?:0.0,
            if(midi==null)0.0 else 1.0,if(midi==null).0001 else .1));t+=.02}}
        voice(55);voice(100,60.0)
        val initial=s.view();assertEquals(60,initial.targetMidi)
        s.cueFinished(initial.cueId,t);voice(14)
        voice(60,60.0);assertEquals(60,s.view().targetMidi)
        voice(5);assertEquals(60,s.view().targetMidi)
        voice(1);assertEquals(64,s.view().targetMidi)
        val a=s.view().attempts.single()
        assertEquals("guided-range-v5",s.submission().protocolVersion)
        assertEquals("voice_ended",a.runEndReason)
        assertEquals(1.0,a.metrics!!.creditedHoldSeconds!!,1e-7)
    }
    @Test fun calibrationWaitsForAQuietWindow() {
        val s=RangeSession("noise")
        repeat(200) {s.onFrame(RangeFrame(it*.02,.02,440.0,1.0,.03))}
        assertEquals("noise",s.view().phase)
        repeat(55) {s.onFrame(RangeFrame(4+it*.02,.02,0.0,0.0,.0002))}
        assertEquals("comfortable",s.view().phase)
        assertEquals(.001,s.view().rmsFloor,1e-9)
    }
    @Test fun selectorRejectsOnlyUnsupportedHarmonicCorrection() {
        val selector=GuidedPitchSelector()
        fun choose(t:Double,initial:Double,corrected:Double,clarity:Double=1.0)=selector.select(
            RangeFrame(t,.02,ScoringMath.midiToHz(corrected),1.0,.1),ScoringMath.midiToHz(initial),clarity,.001,"run")
        repeat(3){choose(it*.02,65.0,65.0)}
        assertEquals("initial_continuity",choose(.06,65.0,46.0).reason)
        assertEquals("corrected",choose(.08,77.0,77.0).reason)
        selector.reset();assertEquals("corrected_no_history",choose(.1,65.0,46.0).reason)
    }
    @Test fun crashCannotPromoteAnUnfinishedPrefixAndKeepsCompletedReplay() {
        val d=Driver();d.seed();d.voice(.8);d.tracker.seal("replay")
        val r=d.tracker.best!!
        val earlier=RangeAttempt("a",60,"anchor",0.0,.5,.75,2.0,1.0,1.8,"not_passed",r.metrics,
            visitId="visit",advanceReason="app_interrupted",selectedForVisit=false,
            referenceStart=.8,referenceEnd=1.0,referenceMidi=60.0,runEndReason="replay")
        val unfinished=earlier.copy(attemptId="b",cueStart=2.0,cueEnd=2.5,responseStart=2.75,responseEnd=4.2,
            evaluationStart=3.0,evaluationEnd=4.2,referenceStart=2.8,referenceEnd=3.0,
            metrics=r.metrics.copy(creditedHoldSeconds=1.2),clientStatus="passed",selectedForVisit=true,
            progressQualified=true,searchOutcome="reached",runEndReason="checkpoint")
        val meta=RangeSubmission("crash",startMidi=60,noiseRms=.0001,durationSeconds=4.2,stopReason="app_interrupted",
            lowerBoundaryStatus="not_probed",upperBoundaryStatus="not_probed",attempts=listOf(earlier,unfinished),registerEvents=emptyList())
        val recovered=meta.recoverAudio(4.6)
        assertEquals(4.6,recovered.durationSeconds,1e-8)
        assertEquals("a",recovered.attempts.single{it.selectedForVisit}.attemptId)
        assertNull(recovered.attempts.last().metrics)
        assertNull(recovered.attempts.last().referenceStart)
        assertFalse(recovered.attempts.last().progressQualified)
        assertEquals("insufficient_quality",recovered.attempts.last().clientStatus)
        val graceful=meta.copy(attempts=listOf(unfinished.copy(runEndReason="user_stopped")))
        assertNotNull(graceful.recoverAudio(4.3).attempts.single().metrics)
    }
}
