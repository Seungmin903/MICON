package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** docs/reference-src/pitch-worklet.test.mjs 의 3개 케이스를 그대로 미러링. */
class YinPitchTest {
    private val rate = 16000.0

    private fun sine(frequency: Double, amplitude: Double = 0.5): FloatArray =
        FloatArray(YinPitch.WINDOW) { i -> (amplitude * sin(2 * PI * frequency * i / rate)).toFloat() }

    @Test
    fun `YIN detects representative singing pitches`() {
        for (frequency in listOf(110.0, 220.0, 440.0, 880.0)) {
            val result = YinPitch.yinPitch(sine(frequency), rate)
            assertTrue("$frequency Hz -> ${result.f0} Hz", abs(result.f0 - frequency) < frequency * 0.015)
            assertTrue("clarity ${result.clarity}", result.clarity > 0.9)
        }
    }

    @Test
    fun `YIN keeps the fundamental when the second harmonic is stronger`() {
        val frequency = 110.0
        val signal = FloatArray(YinPitch.WINDOW) { i ->
            (0.25 * sin(2 * PI * frequency * i / rate) + 0.7 * sin(4 * PI * frequency * i / rate)).toFloat()
        }
        val result = YinPitch.yinPitch(signal, rate)
        assertTrue("expected fundamental, got ${result.f0} Hz", abs(result.f0 - frequency) < 3)
    }

    @Test
    fun `frameRms handles silence and a known constant level`() {
        assertEquals(0.0, YinPitch.frameRms(FloatArray(YinPitch.WINDOW)), 0.0)
        assertTrue(abs(YinPitch.frameRms(FloatArray(YinPitch.WINDOW) { 0.25f }) - 0.25) < 1e-6)
    }

    @Test
    fun `frameRms sub-range matches the worklet half-window gate`() {
        // 앞 절반만 소리가 있는 경계 프레임: rmsLow(두 절반 중 작은 값)는 0 이어야 한다.
        val half = YinPitch.WINDOW / 2
        val buffer = FloatArray(YinPitch.WINDOW) { i -> if (i < half) 0.3f else 0f }
        val rmsLow = minOf(YinPitch.frameRms(buffer, 0, half), YinPitch.frameRms(buffer, half, YinPitch.WINDOW))
        assertEquals(0.0, rmsLow, 0.0)
        assertTrue(YinPitch.frameRms(buffer) > 0.2)
    }
}
