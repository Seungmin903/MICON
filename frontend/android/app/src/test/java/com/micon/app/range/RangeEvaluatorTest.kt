package com.micon.app.range

import com.micon.app.core.network.Json
import com.micon.app.scoring.ScoringMath
import com.micon.app.scoring.YinPitch
import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RangeEvaluatorTest {
    data class Segment(val start: Double, val end: Double, val cents: Double, val clarity: Double = 1.0, val rms: Double = .1, val clip: Double = 0.0)
    data class Fixture(val name: String, val target: Int, val passed: Boolean, val segments: List<Segment>, val expectedHold: Double? = null)

    @Test fun `same protocol fixtures as server`() {
        val json = javaClass.getResourceAsStream("/guided-range.json")!!.bufferedReader().readText()
        val cases = Json.moshi.adapter<List<Fixture>>(Types.newParameterizedType(List::class.java, Fixture::class.java)).fromJson(json)!!
        for (case in cases) {
            val frames = (0 until 100).mapNotNull { i ->
                val t = i * .02
                val s = case.segments.firstOrNull { t >= it.start - 1e-9 && t < it.end - 1e-9 } ?: return@mapNotNull null
                RangeFrame(t, .02, ScoringMath.midiToHz(case.target + s.cents / 100), s.clarity, s.rms, s.rms, s.clip)
            }
            val metrics = RangeEvaluator.evaluate(frames, case.target, 0.0, 2.0, .001)
            assertEquals(case.name, case.passed, metrics.passed)
            case.expectedHold?.let { assertEquals(case.name, it, metrics.longestHoldSeconds, 1e-6) }
        }
    }

    @Test fun `missing timestamps cannot stretch one sample over two seconds`() {
        val frame = RangeFrame(0.0, 2.0, 440.0, 1.0, .1)
        val m = RangeEvaluator.evaluate(listOf(frame), 69, 0.0, 2.0, .001)
        assertFalse(m.passed)
        assertEquals(.02, m.voicedRatio, 1e-8)
    }

    @Test fun `target search band includes C2 and C6 without changing practice defaults`() {
        for (midi in listOf(36, 48, 60, 72, 84)) {
            val hz = ScoringMath.midiToHz(midi.toDouble())
            val signal = FloatArray(YinPitch.WINDOW) { i -> (.15 * sin(2 * PI * hz * i / 16000)).toFloat() }
            val result = YinPitch.yinPitch(signal, 16000.0, 60.0, 1150.0)
            assertTrue("$midi: ${result.f0}", kotlin.math.abs(RangeProtocol.midi(result.f0) - midi) * 100 < 20)
        }
        assertEquals(70.0, YinPitch.FMIN, 0.0)
        assertEquals(1050.0, YinPitch.FMAX, 0.0)
    }

    @Test fun `overlapping duplicate frames do not inflate time`() {
        val f = RangeFrame(0.0, .02, 440.0, 1.0, .1)
        val m = RangeEvaluator.evaluate(List(100) { f }, 69, 0.0, 2.0, .001)
        assertEquals(.01, m.voicedRatio, 1e-8)
    }

    @Test fun `glide and wide vibrato do not pass a straight sustained note task`() {
        for (vibrato in listOf(false, true)) {
            val frames = (0 until 100).map { i ->
                val t = i * .02
                val cents = if (vibrato) 40 * sin(2 * PI * 5 * t) else -100 + 100 * t
                RangeFrame(t, .02, ScoringMath.midiToHz(60 + cents / 100), 1.0, .1)
            }
            assertFalse(RangeEvaluator.evaluate(frames, 60, 0.0, 2.0, .001).passed)
        }
    }

    @Test fun `44100 capture fallback uses actual hop duration`() {
        val hop = 320.0 / 14700.0
        val frames = (0..93).map { i -> RangeFrame(i * hop, hop, 440.0, 1.0, .1) }
        val result = RangeEvaluator.evaluate(frames, 69, 0.0, 2.0, .001)
        assertTrue(result.passed)
        assertEquals(2.0, result.longestHoldSeconds, 1e-8)
    }
}
