package com.micon.app.range

import com.micon.app.data.engine.EngineClient
import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RangeDiagnosticTest {
    private fun frame(t: Double, midi: Double = 60.0, clarity: Double = 1.0, rms: Double = .1,
                      clip: Double = 0.0) = RangeFrame(t, .02, ScoringMath.midiToHz(midi), clarity, rms, clippedRatio = clip)

    @Test fun aSingleBadFrameResetsHoldWhileMedianNeedleStaysInside() {
        for (kind in listOf("confidence", "volume", "spike", "clip", "gap")) {
            val tracker = RelativeHoldTracker(60, 60, .001)
            val display = PitchDisplayFilter()
            repeat(40) { i ->
                val f = frame(i * .02)
                tracker.accept(f); display.update(60.0, f.t, 1, 60, false)
            }
            val f = when (kind) {
                "confidence" -> frame(.8, clarity = .79)
                "volume" -> frame(.8, rms = .0009)
                "spike" -> frame(.8, midi = 60.5)
                "clip" -> frame(.8, clip = .02)
                "gap" -> frame(.82)
                else -> error(kind)
            }
            tracker.accept(f)
            val shown = display.update(if (RangeEvaluator.voiced(f, .001)) RangeProtocol.midi(f.f0) else null,
                f.t, 1, 60, false)
            assertEquals(kind, 60.0, shown!!, 1e-7)
            assertEquals(kind, .8, tracker.best!!.metrics.continuousHoldSeconds, 1e-7)
            assertTrue(kind, (tracker.current?.metrics?.continuousHoldSeconds ?: 0.0) < .03)
            assertEquals(kind, when (kind) {
                "confidence", "volume" -> "invalid_input"
                "spike" -> "reference_drift"
                "clip" -> "clipping"
                else -> "timestamp_gap"
            }, tracker.lastDecision)
        }
    }

    @Test fun withinTargetDriftIsMeasuredAgainstFirstPitchAndRecordedBeforeAutomaticAdvance() {
        val s = RangeSession("diagnostic-test", protocolVersion = "guided-range-v4").apply { diagnosticsEnabled = true }
        var t = 0.0
        fun tick(midi: Double = 60.0, rms: Double = .1) { s.onFrame(frame(t, midi, rms = rms)); t += .02 }
        repeat(151) { tick(rms = if (t < 1) .0001 else .1) }
        val cue = s.view()
        repeat(50) { tick() }; s.cueFinished(cue.cueId, t)
        repeat(14) { tick(rms = .0001) }
        repeat(70) { tick(59.7) } // Includes 0.2 s onset exclusion; current hold exceeds one second.
        assertEquals(60, s.view().targetMidi)
        assertTrue(s.view().bestMetrics!!.passed)
        tick(60.2) // Both pitches within target ±100 cents, but 50 cents from the run's reference.
        val d = s.lastDiagnostic!!
        assertEquals(60, d.targetMidi)
        assertEquals(59.7, d.referenceMidi!!, 1e-7)
        assertTrue(d.holdBefore >= 1.0)
        assertEquals("reference_drift", d.decision)
        assertEquals("passed_run_ended", d.advance)
        assertEquals(64, s.view().targetMidi)
    }

    @Test fun instrumentationDoesNotChangeAnyFrameResult() {
        val plain = RangeSession("same-id", protocolVersion = "guided-range-v4")
        val logged = RangeSession("same-id", protocolVersion = "guided-range-v4").apply { diagnosticsEnabled = true }
        repeat(650) { i ->
            val t = i * .02
            val f = frame(t, midi = if (i % 83 == 0) 60.8 else 60.0,
                rms = if (t < 1 || i % 113 == 0) .0001 else .1)
            val a = plain.onFrame(f); val b = logged.onFrame(f)
            assertEquals("frame $i", a, b)
            if (a.waitingForCue) {
                plain.cueFinished(a.cueId, t); logged.cueFinished(b.cueId, t)
            }
        }
        assertEquals(plain.submission(), logged.submission())
    }

    @Test fun traceIsDurableAndReportsLogLossSeparately() {
        val file = Files.createTempFile("range-diagnostic-test", ".ndjson").toFile()
        try {
            val trace = RangeDiagnosticTrace(file, capacity = 8)
            repeat(2000) { trace.record("captured", mapOf("sequence" to it, "f0" to 440.0)) }
            trace.close()
            val adapter = EngineClient.moshi.adapter(Map::class.java)
            val entries = file.readLines().map { adapter.fromJson(it)!! }
            assertEquals("trace_closed", entries.last()["type"])
            assertEquals(2000L, entries.size - 1L + trace.dropped.get())
            assertEquals(trace.dropped.get().toDouble(), entries.last()["droppedLogEntries"])
            assertNull(trace.failure)
        } finally { file.delete() }
    }
}
