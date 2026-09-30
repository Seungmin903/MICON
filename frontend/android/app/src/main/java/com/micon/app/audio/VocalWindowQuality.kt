package com.micon.app.audio

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class VocalQuality(val usableWindows: Int = 0, val checkedWindows: Int = 0, val fileProblem: Boolean = false) {
    val ready get() = !fileProblem && usableWindows >= VocalWindowQuality.MIN_WINDOWS
    val message get() = if (fileProblem) "녹음 파일을 확인할 수 없어요. 다시 녹음해 주세요."
        else "목소리가 충분히 담긴 구간이 필요해요 ($usableWindows/${VocalWindowQuality.MIN_WINDOWS})"
}

/** Incremental 4 s / 2 s preflight. Native-rate frames retain the model's 2048/512 timing at 44.1 kHz.
 * The server's resampling and model verification remain authoritative. Call only on an IO worker. */
class VocalWindowQuality {
    private var checked = 0
    private var usable = 0
    private var previousBytes = 0L

    @Synchronized fun inspect(file: File, rate: Int, finalized: Boolean = false): VocalQuality {
        if (rate !in setOf(44100, 48000)) return VocalQuality(fileProblem = true)
        val duration = Pcm16MonoWav.duration(file, rate, finalized)
            ?: return VocalQuality(0, checked, fileProblem = finalized)
        if (file.length() < previousBytes) { checked = 0; usable = 0 }
        previousBytes = file.length()
        val totalSamples = (duration * rate).roundToInt().toLong()
        while (checked.toLong() * 2 * rate + 4L * rate <= totalSamples) {
            val samples = Pcm16MonoWav.samples(file, checked.toLong() * 2 * rate, 4 * rate)
            if (usable(samples, rate)) usable++
            checked++
        }
        return VocalQuality(usable, checked)
    }

    companion object {
        const val MIN_WINDOWS = 4
        private const val MIN_RMS = .005

        fun usable(samples: FloatArray, rate: Int): Boolean {
            if (samples.size != 4 * rate || rate !in setOf(44100, 48000)) return false
            val energy = DoubleArray(samples.size + 1)
            var clipped = 0
            for (i in samples.indices) {
                val value = samples[i].toDouble()
                if (!value.isFinite()) return false
                energy[i + 1] = energy[i] + value * value
                if (abs(value) >= .999) clipped++
            }
            if (energy.last() / samples.size < MIN_RMS * MIN_RMS || clipped.toDouble() / samples.size > .01) return false
            // Use the same number and positions of frames as the model, including at 48 kHz.
            val frames = 1 + (4 * 44100 - 2048) / 512
            var active = 0
            repeat(frames) { i ->
                val left = (i * 512.0 * rate / 44100).roundToInt()
                val right = ((i * 512 + 2048.0) * rate / 44100).roundToInt()
                if ((energy[right] - energy[left]) / (right - left) >= MIN_RMS * MIN_RMS) active++
            }
            return active.toDouble() / frames >= .5
        }
    }
}
