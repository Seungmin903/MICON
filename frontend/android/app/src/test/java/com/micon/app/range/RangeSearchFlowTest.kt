package com.micon.app.range

import com.micon.app.core.network.Json
import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RangeSearchFlowTest {
    private class Driver(anchor: Int = 60, guide: Boolean = false) {
        val session = RangeSession("search-v7-$anchor", awaitAnchorConfirmation = guide)
        var time = 0.0
        fun feed(n: Int, midi: Int? = null) { repeat(n) {
            session.onFrame(RangeFrame(time, .02, midi?.let { ScoringMath.midiToHz(it.toDouble()) } ?: 0.0,
                if (midi == null) 0.0 else 1.0, if (midi == null) .0001 else .1))
            time += .02
        } }
        init { feed(55); feed(101, anchor) }
        fun pass() {
            val target = session.view().targetMidi!!
            session.cueFinished(session.view().cueId, time); feed(14)
            feed(70, target); feed(6)
        }
        fun fail(withVoice: Boolean = false) {
            if (withVoice) {
                session.cueFinished(session.view().cueId, time); feed(14)
                feed(35, session.view().targetMidi!!); feed(6)
            } else feed(30)
            assertTrue(session.next(session.view().visitId, time))
        }
    }

    @Test fun oneMidpointEndsEachDirectionForPassedFailedAndSilentSkippedResults() {
        for (upper in 0..2) for (lower in 0..2) {
            val d = Driver(); d.pass()
            assertEquals(66, d.session.view().targetMidi); d.fail()
            assertEquals(63, d.session.view().targetMidi)
            when (upper) { 0 -> d.pass(); 1 -> d.fail(true); else -> d.fail() }
            assertEquals("down", d.session.view().phase)
            assertEquals(54, d.session.view().targetMidi); d.fail()
            assertEquals(57, d.session.view().targetMidi)
            when (lower) { 0 -> d.pass(); 1 -> d.fail(true); else -> d.fail() }
            assertEquals("profile_ready", d.session.view().phase)
            val metadata = d.session.submission()
            assertEquals(listOf(60, 66, 63, 54, 57), metadata.attempts.map { it.targetMidi })
            assertEquals("midpoint_sampled", metadata.upperBoundaryStatus)
            assertEquals("midpoint_sampled", metadata.lowerBoundaryStatus)
            assertEquals("adaptive_6", metadata.sampling!!.strategy)
            export("midpoint-$upper-$lower", metadata.copy(stopReason = "completed"))
        }
    }

    @Test fun additionalMeasurementFillsEveryHoleWithoutRepeatingAnyCompletedTarget() {
        val d = Driver(); d.pass(); d.fail(); d.pass(); d.fail(true); d.fail()
        val basic = d.session.submission()
        val completed = basic.attempts.map { it.targetMidi }.toSet()
        assertTrue(basic.attempts.any { it.metrics == null && it.advanceReason == "manual" })
        assertTrue(basic.attempts.any { it.metrics != null && !it.progressQualified })
        assertTrue(d.session.continueMeasurement(d.time))
        assertFalse(d.session.continueMeasurement(d.time))
        val extra = mutableListOf<Int>()
        while (!d.session.view().done) {
            assertEquals("extension", d.session.view().phase)
            extra += d.session.view().targetMidi!!; d.fail()
        }
        assertEquals(extra.distinct(), extra)
        assertEquals((54..66).toSet() - completed, extra.toSet())
        assertEquals(listOf(64, 65, 59, 58, 56, 55), extra.take(6))
        assertTrue(extra.none { it in completed })
        assertEquals("completed", d.session.submission().sampling!!.refinementStatus)
        // Silent skips do not inflate the actual model-input count.
        assertEquals(basic.sampling!!.measuredNoteCount, d.session.submission().sampling!!.measuredNoteCount)
        export("additional-unvisited", d.session.submission())
    }

    @Test fun rangeEndsImmediatelyChangeDirectionAndNeverAddOutOfRangeNotes() {
        for (anchor in listOf(36, 84)) {
            val d = Driver(anchor); d.pass()
            while (d.session.view().phase != "profile_ready") d.pass()
            val basic = d.session.submission()
            assertTrue(basic.attempts.all { it.targetMidi in 36..84 })
            assertTrue(basic.attempts.none { it.probeStage == "bisect" })
            assertEquals("model_limit", basic.upperBoundaryStatus)
            assertEquals("model_limit", basic.lowerBoundaryStatus)
            assertTrue(d.session.continueMeasurement(d.time))
            val existing = basic.attempts.map { it.targetMidi }.toSet()
            val new = mutableSetOf<Int>()
            while (!d.session.view().done) {
                val midi = d.session.view().targetMidi!!
                assertTrue(midi !in existing && new.add(midi)); d.fail()
            }
            assertEquals((36..84).toSet() - existing, new)
            export("limit-$anchor", basic.copy(stopReason = "completed"))
        }
        val adjacent = RangeSearch(60, 1, 6, adaptive = true, maxMidpointProbes = 1)
        adjacent.record(66, false); adjacent.record(63, false)
        assertNull(adjacent.next()); assertEquals("midpoint_sampled", adjacent.boundary)
    }

    @Test fun onboardingWaitsWithoutCueOrClockAdvanceAndExplicitStartRunsOnlyOnce() {
        val d = Driver(guide = true)
        val paused = d.session.view()
        assertEquals("anchor_ready", paused.phase)
        assertNull(paused.targetMidi); assertNull(paused.cueMidi)
        assertFalse(paused.waitingForCue); assertTrue(paused.inputSuppressed)
        assertTrue(paused.attempts.isEmpty())
        d.feed(200, 60); assertEquals(paused, d.session.view())
        // The caller supplies captured audio time, excluding the guide's wall-clock delay.
        assertTrue(d.session.startAnchorMeasurement(paused.elapsed))
        assertFalse(d.session.startAnchorMeasurement(paused.elapsed))
        assertEquals(60, d.session.view().targetMidi)
        assertTrue(d.session.view().waitingForCue)
        assertEquals(paused.cueId + 1, d.session.view().cueId)
    }

    @Test fun replayRetainsTheCurrentVisitAndNewSessionClearsExclusions() {
        val d = Driver(); d.pass()
        val before = d.session.view()
        d.session.cueFinished(before.cueId, d.time); d.feed(30)
        d.session.replay(d.time)
        assertEquals(before.visitId, d.session.view().visitId)
        assertEquals(before.targetMidi, d.session.view().targetMidi)
        assertEquals(before.cueId + 1, d.session.view().cueId)
        d.fail(); assertEquals(63, d.session.view().targetMidi)
        val fresh = Driver(); fresh.pass(); assertEquals(66, fresh.session.view().targetMidi)
    }

    @Test fun savedTargetHistoryExcludesSilentSkipsAndAllowsUntestedCreditedNotes() {
        val metrics = RangeMetrics(0.0, 0.0, 1.0, 1.0, 1.2, creditedMidi = 61, creditedHoldSeconds = 1.2)
        val parent = RangeReport("guided-range-v7", listOf(RangeNote(61, "passed", metrics)), 61, 61,
            emptyList(), "completed", "midpoint_sampled", "midpoint_sampled", "available", analysisId = "parent",
            sampling = RangeSampling(basicCompleted = true, strategy = "adaptive_6", coarseStep = 6,
                upperLastSuccess = 63, lowerLastSuccess = 60, upperEnd = 66, lowerEnd = 54))
        val completed = setOf(60, 66, 63, 54, 57)
        val s = RangeSession("extension", parent, previousCompletedTargets = completed)
        var time = 0.0
        fun quiet(n: Int) { repeat(n) { s.onFrame(RangeFrame(time, .02, 0.0, 0.0, .0001)); time += .02 } }
        quiet(55)
        val targets = mutableListOf<Int>()
        while (!s.view().done) {
            assertEquals("extension", s.view().phase)
            targets += s.view().targetMidi!!
            quiet(30); assertTrue(s.next(s.view().visitId, time))
        }
        assertEquals((54..66).toSet() - completed, targets.toSet())
        assertTrue(61 in targets)
        assertTrue(targets.none { it in completed })
    }

    private fun export(name: String, submission: RangeSubmission) {
        File("build/range-v7-contract/$name.json").apply {
            parentFile!!.mkdirs(); writeText(Json.moshi.adapter(RangeSubmission::class.java).toJson(submission))
        }
    }
}
