package com.micon.app.range

import com.micon.app.core.network.Json
import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdaptiveRangeTest {
    private class Driver(val midi: Int = 60) {
        val s = RangeSession("adaptive-test", protocolVersion = "guided-range-v6")
        var t = 0.0
        fun voice(n: Int, pitch: Double? = null, clarity: Double = 1.0, clipped: Double = 0.0) {
            repeat(n) {
                s.onFrame(RangeFrame(t,.02,pitch?.let(ScoringMath::midiToHz) ?: 0.0,
                    if(pitch == null) 0.0 else clarity, if(pitch == null) .0001 else .1, clippedRatio=clipped))
                t += .02
            }
        }
        fun start() { voice(55); voice(101,midi.toDouble()); assertEquals(midi,s.view().targetMidi) }
        fun open() { s.cueFinished(s.view().cueId,t); voice(14) }
        fun pass() { val m=s.view().targetMidi!!; open(); voice(70,m.toDouble()); voice(6) }
        fun skip() { voice(30); assertTrue(s.next(s.view().visitId,t)) }
    }

    @Test fun allMidpointOutcomesConvergeInBothDirectionsWithoutDuplicates() {
        for(direction in listOf(-1,1)) for(mask in 0..7) {
            val search=RangeSearch(60,direction,8)
            assertEquals(60+direction*8,search.next())
            search.record(search.next()!!,false)
            val seen=mutableSetOf(60,60+direction*8)
            repeat(3) { i ->
                val pair=search.boundaryPair!!
                assertEquals(8 shr i,kotlin.math.abs(pair[0]-pair[1]))
                val m=search.next()!!;assertTrue(seen.add(m))
                assertEquals((pair[0]+pair[1])/2,m)
                assertEquals("bisect",search.stage)
                search.record(m,mask and (1 shl i) != 0)
            }
            assertNull(search.next());assertEquals("boundary_refined",search.boundary)
            assertEquals(direction,search.boundaryPair!![1]-search.boundaryPair!![0])
            assertEquals(60+direction*8,search.frontier)
            assertEquals(search.frontier,search.firstFailure)
        }
    }

    @Test fun coarseTargetsStayOnEightSemitoneGridAndNeverClamp() {
        for(direction in listOf(-1,1)) {
            val search=RangeSearch(60,direction,8)
            repeat(3) { i -> assertEquals(60+direction*8*(i+1),search.next());search.record(search.next()!!,true) }
            assertNull(search.next());assertEquals("model_limit",search.boundary)
            assertNull(search.boundaryPair)
        }
        assertNull(RangeSearch(78,1,8).next())
        assertNull(RangeSearch(40,-1,8).next())
    }

    @Test fun passedAnchorImmediatelyStartsEightSemitoneSweepAndLowScoreStillProgresses() {
        val d=Driver();d.start();assertTrue(d.s.view().canResetAnchor)
        d.pass();assertEquals(68,d.s.view().targetMidi);assertEquals("coarse",d.s.view().probeStage)
        assertTrue(d.s.view().attempts.single().metrics!!.stabilityScore!! < 80)
        d.pass();assertEquals(76,d.s.view().targetMidi)
        assertEquals(listOf(60,68),d.s.view().attempts.map{it.targetMidi})
    }

    @Test fun shortRunsSilenceAndTrackingErrorsDoNotSwitchTargetsOrCreateTimeouts() {
        val d=Driver();d.start();d.pass();d.open()
        d.voice(50,68.0);d.voice(6);assertEquals(68,d.s.view().targetMidi)
        d.voice(120,68.0,clarity=.79);d.voice(120,68.0,clipped=.02)
        d.voice(5000);assertEquals(68,d.s.view().targetMidi)
        assertEquals("coarse",d.s.view().probeStage)
        assertTrue(d.s.next(d.s.view().visitId,d.t))
        assertEquals(64,d.s.view().targetMidi)
        assertEquals("not_passed",d.s.view().attempts.last().clientStatus)
    }

    @Test fun oneSecondDoesNotAdvanceUntilConfirmedEndOrFullTwoAndHalfSeconds() {
        val d=Driver();d.start();d.open()
        d.voice(61,60.0);assertEquals(60,d.s.view().targetMidi)
        d.voice(5);assertEquals(60,d.s.view().targetMidi)
        d.voice(1);assertEquals(68,d.s.view().targetMidi)
        d.open();d.voice(134,68.0);assertEquals(68,d.s.view().targetMidi)
        d.voice(1,68.0);assertEquals(76,d.s.view().targetMidi)
    }

    @Test fun anchorResetPreservesEvidenceAndIgnoresLateCueAndDuplicateButton() {
        val d=Driver();d.start();d.open();d.voice(45,60.6)
        val before=d.s.view();assertTrue(d.s.next(before.visitId,d.t))
        assertFalse(d.s.next(before.visitId,d.t+.6))
        assertEquals("comfortable",d.s.view().phase);assertNull(d.s.view().targetMidi)
        d.s.cueFinished(before.cueId,d.t+1);assertEquals("comfortable",d.s.view().phase)
        val old=d.s.view().attempts.single()
        assertEquals("anchor_reset",old.advanceReason);assertFalse(old.progressQualified)
        assertEquals(60,old.anchorMidi);assertNotNull(old.metrics)
        assertNull(d.s.submission().startMidi)
        d.voice(12,60.0);assertNull(d.s.view().targetMidi)
        d.voice(101,64.0);assertEquals(64,d.s.view().targetMidi)
        d.pass();assertEquals(72,d.s.view().targetMidi)
        assertEquals(60,d.s.view().attempts.first().anchorMidi)
        assertEquals(64,d.s.view().attempts.last().anchorMidi)
        export("anchor-reset",d.s.stop(audioTime=d.t))
    }

    @Test fun basicSweepChoiceAndAdditionalNotesPreserveUnmeasuredHoles() {
        val d=Driver();d.start();d.pass()
        val stale=d.s.view();d.skip();assertEquals(64,d.s.view().targetMidi)
        assertFalse(d.s.next(stale.visitId,d.t));d.s.cueFinished(stale.cueId,d.t)
        assertTrue(d.s.view().waitingForCue)
        d.pass();assertEquals(66,d.s.view().targetMidi)
        d.skip();assertEquals(65,d.s.view().targetMidi)
        d.pass();assertEquals(52,d.s.view().targetMidi)
        d.skip();assertEquals(56,d.s.view().targetMidi)
        d.pass();assertEquals(54,d.s.view().targetMidi)
        d.skip();assertEquals(55,d.s.view().targetMidi)
        d.pass();assertEquals("profile_ready",d.s.view().phase)
        val view=d.s.view();val meta=d.s.submission()
        assertEquals(listOf(65,66),meta.sampling!!.upperBoundaryPair)
        assertEquals(listOf(55,54),meta.sampling!!.lowerBoundaryPair)
        assertEquals(68,meta.sampling!!.upperEnd);assertEquals(52,meta.sampling!!.lowerEnd)
        assertTrue(meta.sampling!!.basicCompleted)
        assertEquals("boundary_refined",meta.lowerBoundaryStatus)
        assertTrue(meta.attempts.filter{it.advanceReason=="manual"}.all{it.clientStatus=="unmeasured" && it.metrics==null})
        assertEquals(meta.attempts.size,meta.attempts.map{it.targetMidi}.distinct().size)
        export("basic",meta.copy(stopReason="completed"))
        d.voice(500);assertEquals(view,d.s.view())
        assertTrue(d.s.continueMeasurement(d.t));assertFalse(d.s.continueMeasurement(d.t))
        assertEquals(65,d.s.view().targetMidi);d.skip();assertEquals(55,d.s.view().targetMidi)
        val additional=mutableListOf(65)
        while(!d.s.view().done) { additional.add(d.s.view().targetMidi!!);d.skip() }
        assertEquals(additional.distinct(),additional)
        assertTrue(additional.all{it in 52..68})
        assertEquals("completed",d.s.submission().sampling!!.refinementStatus)
        export("extended",d.s.submission())
    }

    @Test fun recoveryKeepsV6SamplingAndDoesNotPromoteLivePrefix() {
        val d=Driver();d.start();d.pass();d.open();d.voice(70,68.0)
        val recovered=d.s.submission().recoverAudio(d.t+.1)
        assertEquals("adaptive_8",recovered.sampling!!.strategy)
        assertEquals("insufficient_quality",recovered.attempts.last().clientStatus)
        assertEquals("passed",recovered.attempts.first().clientStatus)
        export("recovered",recovered)
    }

    private fun export(name: String, submission: RangeSubmission) {
        val file=File("build/range-v6-contract/$name.json");file.parentFile!!.mkdirs()
        file.writeText(Json.moshi.adapter(RangeSubmission::class.java).toJson(submission))
    }
}
