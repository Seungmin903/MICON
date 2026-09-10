package com.micon.app.range

import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test

class RelativeHoldTest {
    private fun frame(t: Double, midi: Double?, clip: Double = 0.0) = RangeFrame(t, .02,
        midi?.let(ScoringMath::midiToHz) ?: 0.0, if (midi == null) 0.0 else 1.0, .1, clippedRatio = clip)
    private fun track(midi: Double, count: Int = 125, target: Int = 64, anchor: Int = 60): RelativeHoldTracker {
        val tracker = RelativeHoldTracker(target, anchor, .001)
        repeat(count) { tracker.accept(frame(it * .02, midi)) }; return tracker
    }
    @Test fun offsetIsAcceptedAndTwoPointFiveSecondsIsFullScore() {
        val best = track(64.35).best!!
        assertTrue(best.metrics.passed)
        assertEquals(2.5, best.metrics.continuousHoldSeconds, 1e-8)
        assertEquals(100.0, best.metrics.stabilityScore!!, 1e-7)
        assertEquals(64.35, best.metrics.actualMidi!!, 1e-8)
        assertEquals(64, best.metrics.creditedMidi)
    }
    @Test fun boundariesAndConservativeAttributionUseActualPitch() {
        assertNull(track(65.01).best); assertNull(track(76.0).best)
        assertTrue(track(65.0).best!!.metrics.passed)
        assertEquals(63, track(63.8).best!!.metrics.creditedMidi)
        assertEquals(57, track(56.2, target=56).best!!.metrics.creditedMidi)
        assertEquals(36, RelativeHoldTracker.credit(35.8, 36, 60))
        assertNull(RelativeHoldTracker.credit(85.0, 84, 60))
    }
    @Test fun interruptionsAreNotJoinedAndShortBestIsPreserved() {
        for (kind in listOf("silence", "missing", "clip", "drift")) {
            val t = RelativeHoldTracker(64,60,.001)
            repeat(40) { t.accept(frame(it*.02,64.2)) }
            if (kind != "missing") t.accept(frame(.8, if(kind=="silence") null else if(kind=="drift") 65.0 else 64.2,
                if(kind=="clip") .02 else 0.0))
            repeat(30) { t.accept(frame(.82+it*.02,64.2)) }
            assertEquals(kind,.8,t.best!!.metrics.continuousHoldSeconds,1e-8)
            assertFalse(t.best!!.metrics.passed)
        }
    }
    @Test fun longestRecordWinsAndEqualDurationKeepsFirst() {
        val t = RelativeHoldTracker(64,60,.001)
        repeat(50) { t.accept(frame(it*.02,64.2)) }
        t.accept(frame(1.0,null))
        repeat(50) { t.accept(frame(1.02+it*.02,64.3)) }
        assertEquals(0.0,t.best!!.start,1e-8)
        repeat(5) { t.accept(frame(2.02+it*.02,64.3)) }
        assertEquals(1.02,t.best!!.start,1e-8)
    }
    @Test fun scoreIncludesDurationAndSteadinessAndDoesNotRewardTinySounds() {
        val steady = track(64.2).best!!.metrics
        val t = RelativeHoldTracker(64,60,.001)
        repeat(125) { t.accept(frame(it*.02,64.2 + if(it%2==0) .1 else -.1)) }
        assertEquals(steady.continuousHoldSeconds,t.best!!.metrics.continuousHoldSeconds,1e-8)
        assertTrue(t.best!!.metrics.stabilityScore!! < steady.stabilityScore!!)
        assertTrue(track(64.2,1).best!!.metrics.stabilityScore!! < 2)
        assertFalse(track(64.2,49).best!!.metrics.passed)
        assertTrue(track(64.2,50).best!!.metrics.passed)
    }

    private class Driver {
        val s = RangeSession("relative-test", protocolVersion = "guided-range-v4"); var t = 0.0
        fun tick(m: Double? = null, clip: Double = 0.0) { s.onFrame(RangeFrame(t,.02,m?.let(ScoringMath::midiToHz) ?: 0.0,
            if(m==null)0.0 else 1.0,if(m==null).0001 else .1, clippedRatio=clip)); t+=.02 }
        fun cue() { val v=s.view(); repeat((v.cueDurationSeconds/.02).toInt()){tick(v.targetMidi!!.toDouble())}
            s.cueFinished(v.cueId,t); repeat(14){tick()} }
        fun prepare() { repeat(151){tick(if(t<1)null else 60.0)}; cue() }
        fun voice(n: Int) { val target=s.view().targetMidi!!.toDouble(); repeat(n){tick(target+.35)} }
    }
    @Test fun manualMoveBeforePassKeepsShortLongestRecord() {
        val d=Driver(); d.prepare(); d.voice(50)
        assertEquals(60,d.s.view().targetMidi)
        assertFalse(d.s.view().bestMetrics!!.passed)
        val best=d.s.view().bestMetrics!!
        assertTrue(d.s.next(d.s.view().visitId,d.t))
        val a=d.s.view().attempts.single()
        assertEquals("not_passed",a.clientStatus); assertEquals("manual_failed",a.searchOutcome)
        assertEquals(best,a.metrics)
        assertEquals("guided-range-v4",d.s.submission().protocolVersion)
        assertTrue(a.evaluationEnd!!-a.evaluationStart!! < 1.0)
    }
    @Test fun replayAndStopKeepEarlierLongestWithoutJoiningCue() {
        val d=Driver(); d.prepare(); d.voice(50)
        val before=d.s.view().bestMetrics!!.continuousHoldSeconds
        d.s.replay(d.t); d.cue(); d.voice(30)
        val data=d.s.stop(audioTime=d.t)
        val selected=data.attempts.single{it.selectedForVisit}
        assertEquals(before,selected.metrics!!.continuousHoldSeconds,1e-8)
        assertEquals("not_passed",selected.clientStatus)
        assertTrue(data.attempts.size==2)
    }
    @Test fun oneSecondPassWaitsForBreakAndStaleActionsCannotSkipAgain() {
        val d=Driver(); d.prepare(); assertNull(d.s.view().bestMetrics)
        val old=d.s.view()
        d.voice(59); assertEquals(60,d.s.view().targetMidi)
        assertEquals(.98f/2.5f,d.s.view().holdProgress,1e-6f)
        d.voice(1); assertEquals(60,d.s.view().targetMidi)
        assertTrue(d.s.view().bestMetrics!!.passed)
        assertEquals(.4f,d.s.view().holdProgress,1e-6f)
        d.tick(); assertEquals(64,d.s.view().targetMidi)
        val attempt=d.s.view().attempts.single()
        assertEquals(1.0,attempt.metrics!!.continuousHoldSeconds,1e-8)
        assertEquals(.02,attempt.responseEnd-attempt.evaluationEnd!!,1e-8)
        assertEquals("passed",attempt.clientStatus)
        assertEquals("threshold",attempt.advanceReason)
        assertFalse(d.s.next(old.visitId,d.t))
        d.s.cueFinished(old.cueId,d.t)
        assertTrue(d.s.view().waitingForCue)
        assertEquals(64,d.s.view().targetMidi)
        assertEquals(1,d.s.view().attempts.size)
    }
    @Test fun uninterruptedVoiceContinuesUntilTwoPointFiveSeconds() {
        val d=Driver(); d.prepare(); d.voice(134)
        assertEquals(60,d.s.view().targetMidi)
        assertEquals(2.48,d.s.view().bestMetrics!!.continuousHoldSeconds,1e-8)
        d.voice(1)
        assertEquals(64,d.s.view().targetMidi)
        val a=d.s.view().attempts.single()
        assertEquals(2.5,a.metrics!!.continuousHoldSeconds,1e-8)
        assertEquals(100.0,a.metrics!!.stabilityScore!!,1e-7)
    }
    @Test fun breaksOnlyAdvanceCompletedPassesAndNeverAddGapTime() {
        for (kind in listOf("silence","missing","clip","drift")) {
            for (passed in listOf(false,true)) {
                val d=Driver(); d.prepare(); d.voice(if(passed)85 else 50)
                val before=d.s.view().bestMetrics!!
                assertEquals(passed,before.passed)
                when(kind) {
                    "silence" -> d.tick()
                    "missing" -> { d.t+=.02; d.tick(60.35) }
                    "clip" -> d.tick(60.35,.02)
                    "drift" -> d.tick(61.0)
                }
                assertEquals(kind,if(passed)64 else 60,d.s.view().targetMidi)
                if(passed) assertEquals(before,d.s.view().attempts.single().metrics)
                else {
                    assertTrue(d.s.view().attempts.isEmpty())
                    assertEquals(before,d.s.view().bestMetrics)
                }
            }
        }
    }
    @Test fun shortBreakCanRetryAndReplayDoesNotCompleteEarlierPass() {
        val d=Driver(); d.prepare(); d.voice(50); d.tick()
        d.voice(60)
        assertEquals(60,d.s.view().targetMidi)
        assertEquals(1.2,d.s.view().bestMetrics!!.continuousHoldSeconds,1e-8)
        d.s.replay(d.t); d.cue(); d.voice(30); d.tick()
        assertEquals(60,d.s.view().targetMidi)
        assertTrue(d.s.next(d.s.view().visitId,d.t))
        val selected=d.s.view().attempts.single{it.selectedForVisit}
        assertEquals(1.2,selected.metrics!!.continuousHoldSeconds,1e-8)
        assertEquals("passed",selected.clientStatus)
    }
    @Test fun scoreGraphHasGapsAndUsesCompositeScore() {
        val m=track(64.2,50).best!!.metrics
        val plot=RangeStabilityPlot.from(listOf(RangeNote(60,"passed",m),RangeNote(62,"not_passed",m)),scoreBased=true)
        assertEquals(.7f,plot.points.first{it.note.midi==60}.ratio!!,1e-6f)
        assertTrue(plot.segments.isEmpty())
    }
    @Test fun centralPassesThenTwoManualFailuresSwitchEachDirection() {
        val d=Driver(); d.prepare(); d.voice(140)
        repeat(2) { d.cue(); d.voice(140) }
        assertEquals("up",d.s.view().phase); assertEquals(66,d.s.view().targetMidi)
        fun skip() { repeat(30){d.tick()}; assertTrue(d.s.next(d.s.view().visitId,d.t)) }
        skip(); assertEquals(68,d.s.view().targetMidi)
        skip(); assertEquals("down",d.s.view().phase); assertEquals(54,d.s.view().targetMidi)
        skip(); skip(); assertEquals("profile_ready",d.s.view().phase)
        assertTrue(d.s.view().sampling.basicCompleted)
        assertTrue(d.s.view().attempts.takeLast(4).all{it.clientStatus=="unmeasured" && it.searchOutcome=="manual_failed"})
    }
    @Test fun recoveryKeepsCompletedPassAfterNextCueTailIsLost() {
        val d=Driver(); d.prepare(); d.voice(60); d.tick()
        val durable=d.s.view().attempts.single().responseEnd
        d.cue(); d.voice(30)
        val original=d.s.submission(); val recovered=original.recoverAudio(durable)
        val selected=recovered.attempts.single{it.selectedForVisit}
        assertTrue(selected.metrics!!.passed)
        assertTrue(recovered.attempts.all{it.progressQualified && it.searchOutcome=="reached"})
        assertEquals("app_interrupted",recovered.stopReason)
        assertEquals(original.attempts.first().metrics,selected.metrics)
    }
}
