package com.micon.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CaptureRatesTest {
    @Test fun timbreNeverFallsBackToLowBandwidthCapture() {
        assertArrayEquals(intArrayOf(48000, 44100), captureRates(true))
        assertArrayEquals(intArrayOf(16000, 48000, 44100), captureRates(false))
    }
}
