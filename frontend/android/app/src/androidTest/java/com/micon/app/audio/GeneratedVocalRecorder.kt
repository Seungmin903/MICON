package com.micon.app.audio

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Deterministic original-rate PCM on a real recording clock; no microphone or server analysis. */
class GeneratedVocalRecorder : PcmRecorder {
    @Volatile var audible = true
    @Volatile override var capturedSeconds = 0.0; private set
    override val recordingRate = 44100
    override val analysisRate = 44100.0
    @Volatile override var isRunning = false; private set
    private var worker: Thread? = null
    override fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = begin(file, false, onError, onFrame)
    override fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = begin(file, true, onError, onFrame)
    private fun begin(file: File, append: Boolean, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) {
        check(!isRunning)
        var samples = if (append) (file.length() - 44) / 2 else 0L
        capturedSeconds = samples.toDouble() / recordingRate
        isRunning = true
        worker = Thread {
            try {
                FileOutputStream(file, append).use { stream ->
                    if (!append) stream.write(ByteArray(44))
                    var next = SystemClock.elapsedRealtime()
                    while (isRunning) {
                        val block = ByteBuffer.allocate(882 * 2).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(882) { i -> block.putShort(if (audible) (2500 * sin(2 * PI * 261.625565 * (samples + i) / recordingRate)).toInt().toShort() else 0) }
                        stream.write(block.array())
                        samples += 882
                        capturedSeconds = samples.toDouble() / recordingRate
                        onFrame(PitchCapture.PitchFrame(capturedSeconds, 0.0, 0.0, if (audible) .05 else 0.0, if (audible) .05 else 0.0))
                        next += 20
                        SystemClock.sleep((next - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                }
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()).putInt(36 + (samples * 2).toInt()).put("WAVEfmt ".toByteArray()).putInt(16)
                    putShort(1).putShort(1).putInt(recordingRate).putInt(recordingRate * 2).putShort(2).putShort(16)
                    put("data".toByteArray()).putInt((samples * 2).toInt())
                }
                RandomAccessFile(file, "rw").use { it.write(header.array()) }
            } catch (failure: Exception) { onError(failure) }
            finally { isRunning = false }
        }.also { it.start() }
    }
    override fun stop() { isRunning = false; worker?.join(2000); worker = null }
}
