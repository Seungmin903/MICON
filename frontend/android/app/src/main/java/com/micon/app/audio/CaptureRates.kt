package com.micon.app.audio

/** Do not silently fall back to 16 kHz for timbre: upsampling cannot restore lost bandwidth. */
internal fun captureRates(timbreRecording: Boolean): IntArray =
    if (timbreRecording) intArrayOf(48000, 44100) else intArrayOf(16000, 48000, 44100)
