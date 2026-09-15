package com.micon.app.range

import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test

class RangeSessionTest {
    private class Driver(val s: RangeSession = RangeSession("test", protocolVersion = "guided-range-v3")) {
        var t = 0.0
        fun tick(midi: Double? = null, clip: Double = 0.0) {
            s.onFrame(RangeFrame(t, .02, midi?.let(ScoringMath::midiToHz) ?: 0.0,
                if (midi == null) 0.0 else 1.0, if (midi == null) .0001 else .1, clippedRatio = clip))
            t += .02
        }
        fun prepare(midi: Double = 60.0) {
            repeat(151) { tick(if (t < 1) null else midi) }
            assertEquals("anchor", s.view().phase)
        }
        fun cue() {
            val v = s.view(); check(v.waitingForCue)
            repeat((v.cueDurationSeconds / .02).toInt()) { tick(v.cueMidi!!.toDouble()) }
            s.cueFinished(v.cueId, t); repeat(14) { tick() }
        }
        fun voice(seconds: Double, good: Int = 100, period: Int = 100, offset: Double = 0.0, clip: Double = 0.0) {
            val target = s.view().targetMidi!!.toDouble()
            repeat((seconds / .02).toInt()) { i -> tick(target + offset + if (i % period < good) 0.0 else 2.0, clip) }
        }
        fun next() = s.next(s.view().visitId, t)
        fun skip() { if (s.view().waitingForCue) cue(); repeat(30) { tick() }; assertTrue(next()) }
        fun pass() { cue(); voice(2.6) }
        fun centralPass() { prepare(); repeat(3) { pass() } }
        fun ready() { centralPass(); repeat(4) { skip() }; assertEquals("profile_ready", s.view().phase) }
    }
    @Test fun sweepAlwaysStepsTwoAndEndsAfterTwoProbeFailures() {
        for (dir in listOf(-1, 1)) {
            val s = RangeSearch(60, dir)
            assertEquals(60+2*dir,s.next()); s.record(60+2*dir,false)
            assertEquals(60+4*dir,s.next()); s.record(60+4*dir,true)
            assertEquals(60+6*dir,s.next()); s.record(60+6*dir,false)
            assertEquals(60+8*dir,s.next()); s.record(60+8*dir,false)
            assertNull(s.next()); assertEquals("two_probe_failures",s.boundary)
        }
        val edge = RangeSearch(83,1)
        assertNull(edge.next()); assertEquals("model_limit",edge.boundary)
    }
    @Test fun fragmentedHalfNeverAdvancesEvenAfterTenMinutes() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(610.0,25,50)
        assertEquals("anchor",d.s.view().phase)
        assertEquals(.5,d.s.view().bestMetrics!!.inTuneRatio,.0001)
        assertEquals(.5,d.s.view().metrics!!.continuousHoldSeconds,.0001)
        assertTrue(d.next())
        assertEquals("manual_failed",d.s.submission().attempts.first().searchOutcome)
    }
    @Test fun continuousSecondStillRequiresFullWindowAndIsNotStrictPass() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(1.3)
        assertEquals("anchor",d.s.view().phase); assertNull(d.s.view().metrics)
        d.voice(3.0,50)
        val a=d.s.submission().attempts.first()
        assertEquals("threshold",a.advanceReason)
        assertTrue(a.progressQualified)
        assertEquals(2.0,a.progressEvaluationEnd!!-a.progressEvaluationStart!!,.0001)
    }
    @Test fun continuousHalfAdvancesButStrictStabilityRemainsSeparate() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(3.0,50)
        val a=d.s.submission().attempts.first()
        assertEquals("not_passed",a.clientStatus); assertTrue(a.progressQualified)
        assertEquals(.5,a.metrics!!.inTuneRatio,.0001)
        assertTrue(a.progressEvaluationStart!! >= a.evaluationStart!!)
    }
    @Test fun oneMissingFrameBreaksContinuousHoldButNotLegacyBridgedHold() {
        val frames=(0 until 100).map { i -> RangeFrame(i*.02,.02,if (i==49) 0.0 else ScoringMath.midiToHz(60.0),1.0,.1) }
        val m=RangeEvaluator.evaluate(frames,60,0.0,2.0,.001)
        assertEquals(1.98,m.longestHoldSeconds,.0001)
        assertEquals(1.0,m.continuousHoldSeconds,.0001)
        val missing=frames.filterIndexed { i,_ -> i != 50 }
        assertTrue(RangeEvaluator.evaluate(missing,60,0.0,2.0,.001).continuousHoldSeconds < 1)
    }
    @Test fun exactPassAndPersistentBiasAdvancementAreDistinct() {
        for (offset in listOf(0.0,.35)) {
            val d=Driver(); d.prepare(); d.cue(); d.voice(2.6,offset=offset)
            val a=d.s.submission().attempts.first()
            assertEquals(if(offset==0.0) "passed" else "not_passed",a.clientStatus)
            assertTrue(a.progressQualified)
        }
    }
    @Test fun wrongOctaveSemitoneSilenceAndClippingDoNotAdvance() {
        for(offset in listOf(1.0,12.0,-12.0)) {
            val d=Driver(); d.prepare(); d.cue(); d.voice(6.0,offset=offset)
            assertEquals("anchor",d.s.view().phase)
        }
        val d=Driver(); d.prepare(); d.cue(); repeat(5000) { d.tick() }; d.voice(4.0,clip=.02)
        assertEquals("anchor",d.s.view().phase); assertNull(d.s.view().bestMetrics)
        d.next(); assertEquals("insufficient_quality",d.s.submission().attempts.first().clientStatus)
    }
    @Test fun stableCentralNotesSkipToSixAndSweepUpBeforeDown() {
        val d=Driver(); d.centralPass()
        assertEquals(66,d.s.view().targetMidi); assertEquals("up",d.s.view().phase)
        d.skip(); assertEquals(68,d.s.view().targetMidi)
        d.skip(); assertEquals(54,d.s.view().targetMidi); assertEquals("down",d.s.view().phase)
        d.skip(); assertEquals(52,d.s.view().targetMidi); d.skip()
        assertEquals("profile_ready",d.s.view().phase); assertFalse(d.s.view().done)
        assertEquals(listOf(listOf(61,63),listOf(57,59)),d.s.submission().sampling!!.skippedCentralIntervals)
    }
    @Test fun unstableCentralUsesTwoAndReusesFourInSweepOrder() {
        val d=Driver(); d.prepare(); d.pass(); d.skip(); d.pass()
        assertEquals(62,d.s.view().targetMidi)
        d.skip() // 62 and cached 64 fail; switch down without replaying 64.
        assertEquals(54,d.s.view().targetMidi)
        assertEquals(1,d.s.submission().attempts.count { it.targetMidi==64 })
    }
    @Test fun reachingCentralWithoutStrictStabilityDoesNotSkip() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(2.6,offset=.35); d.pass(); d.pass()
        assertEquals(62,d.s.view().targetMidi)
        assertTrue(d.s.submission().sampling!!.skippedCentralIntervals.isEmpty())
    }
    @Test fun allManualIsSearchFailureButHasNoInventedNoteScores() {
        val d=Driver(); d.prepare(); repeat(5) { d.skip() }
        val result=d.s.stop("completed",d.t)
        assertTrue(result.sampling!!.basicCompleted)
        assertEquals(0,result.sampling!!.measuredNoteCount)
        assertTrue(result.attempts.all { it.clientStatus=="unmeasured" && it.metrics==null && it.searchOutcome=="manual_failed" })
        assertEquals("two_probe_failures",result.lowerBoundaryStatus)
        assertTrue(RangeKeyboardState.notes(emptyList(),result.attempts).all { it.status=="unmeasured" })
    }
    @Test fun staleCueAndDoubleTapCannotSkipTwoNotes() {
        val d=Driver(); d.prepare()
        val v=d.s.view(); assertTrue(d.next())
        assertFalse(d.s.next(v.visitId,d.t)); assertFalse(d.next())
        d.s.cueFinished(v.cueId,d.t+1)
        assertTrue(d.s.view().waitingForCue)
        assertEquals(64,d.s.view().targetMidi)
    }
    @Test fun highestFragmentedWindowAndDifferentAdvanceWindowAreBothPreserved() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(4.0,40,50)
        val peak=d.s.view().bestMetrics!!
        assertEquals(.8,peak.inTuneRatio,.0001)
        d.s.replay(d.t); d.cue(); d.voice(3.0,50)
        val group=d.s.submission().attempts.filter { it.direction=="anchor" }
        assertEquals(2,group.size)
        val best=group.single { it.selectedForVisit }
        val progress=group.single { it.progressEvaluationStart!=null }
        assertEquals(peak,best.metrics); assertNotEquals(best.attemptId,progress.attemptId)
        assertTrue(best.evaluationEnd!! < progress.progressEvaluationStart!!)
        assertTrue(group.all { it.progressQualified })
    }
    @Test fun manualReplayKeepsPeakAndEarliestTie() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(4.0,40,50)
        val first=d.s.submission().attempts.single().evaluationStart
        d.voice(4.0,40,50); assertEquals(first,d.s.submission().attempts.single().evaluationStart)
        d.s.replay(d.t); d.cue(); d.voice(4.0,20,50); d.next()
        assertEquals(first,d.s.submission().attempts.single { it.selectedForVisit && it.direction=="anchor" }.evaluationStart)
    }
    @Test fun checkpointsAndTruncatedRecoveryKeepAnEarlierWholeScore() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(4.0,40,50)
        val before=d.s.submission(); d.voice(20.0,0)
        val snapshot=d.s.submission(); val recovered=snapshot.recoverAudio(d.t-1)
        assertEquals(before.attempts.single().metrics,recovered.attempts.single().metrics)
        assertEquals("app_interrupted",recovered.stopReason)
        val empty=snapshot.recoverAudio(before.attempts.single().evaluationEnd!!-.5)
        assertNull(empty.attempts.single().metrics)
        assertEquals(snapshot.attempts.single().metrics,d.s.stop(audioTime=d.t).attempts.single().metrics)
    }
    @Test fun choiceFreezesAudioEvaluationAndContinueIsIdempotent() {
        val d=Driver(); d.ready()
        val t=d.s.view().elapsed; val records=d.s.submission().attempts
        repeat(5000) { d.tick(60.0) }
        assertEquals(t,d.s.view().elapsed,0.0)
        assertEquals(records,d.s.submission().attempts)
        assertTrue(d.s.continueMeasurement(t)); assertFalse(d.s.continueMeasurement(t))
        assertEquals(64,d.s.view().targetMidi)
        assertTrue(d.s.view().waitingForCue)
        val stop=d.s.stop("app_interrupted",t)
        assertTrue(stop.sampling!!.basicCompleted)
        assertEquals("in_progress",stop.sampling!!.refinementStatus)
    }
    @Test fun refinementRechecksBothEdgesThenHolesWithoutDuplicatesOrExpansion() {
        val sample=RangeSampling(true,upperLastSuccess=64,lowerLastSuccess=56,upperEnd=68,lowerEnd=52)
        val targets=RangeRefinement.targets(60,sample,setOf(60,64,56,66,68,54,52))
        assertEquals(listOf(64,56,65,67,55,53,57,58,59,61,62,63),targets)
        val d=Driver(); d.ready(); d.s.continueMeasurement(d.t)
        val seen=mutableListOf<Int>()
        repeat(49) {
            if(!d.s.view().done) { seen.add(d.s.view().targetMidi!!); d.skip() }
        }
        assertTrue(d.s.view().done)
        assertEquals(seen.distinct(),seen); assertTrue(seen.all { it in 52..68 })
        assertEquals("completed",d.s.submission().sampling!!.refinementStatus)
    }
    @Test fun technicalBoundsNeverClampOrForceAnExtraSemitone() {
        val d=Driver(); d.prepare(83.0); d.pass(); d.pass()
        assertEquals("down",d.s.view().phase); assertEquals(77,d.s.view().targetMidi)
        assertEquals("model_limit",d.s.submission().upperBoundaryStatus)
        assertFalse(d.s.submission().attempts.any { it.targetMidi==84 })
    }
    @Test fun stableFullSweepWaitsForChoiceBeyondNinetySeconds() {
        val d=Driver(); d.prepare(); d.cue(); d.voice(100.0,offset=2.0); d.voice(2.6)
        repeat(49) { if(d.s.view().targetMidi!=null) d.pass() }
        assertEquals("profile_ready",d.s.view().phase)
        assertTrue(d.s.view().elapsed>90)
        assertEquals("model_limit",d.s.submission().upperBoundaryStatus)
        assertEquals("model_limit",d.s.submission().lowerBoundaryStatus)
    }
    @Test fun oldVersionsRemainReadableButCannotMixWithNewExtension() {
        assertTrue(RangeProtocol.supports("guided-range-v1")); assertTrue(RangeProtocol.supports("guided-range-v2"))
        val parent=RangeReport("guided-range-v2",listOf(RangeNote(60,"passed",successfulAttempts=1)),60,60,
            listOf(listOf(60,60)),"completed","model_limit","model_limit","available","parent")
        assertThrows(IllegalArgumentException::class.java) { RangeSession("bad",parent) }
    }
}
