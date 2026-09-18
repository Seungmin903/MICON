package com.micon.app.ui.report

import com.micon.app.audio.PitchCapture
import java.io.File

/** Capture lifecycle kept separate so guide pauses can be verified without a real microphone. */
interface RangeRecorder {
    val capturedSeconds: Double
    val recordingRate: Int
    val analysisRate: Double
    val isRunning: Boolean
    fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit)
    fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit)
    fun stop()
}

internal class MicrophoneRangeRecorder : RangeRecorder {
    private val capture = PitchCapture(fmin = 60.0, fmax = 1150.0, collectCandidates = true)
    override val capturedSeconds get() = capture.capturedSeconds
    override val recordingRate get() = capture.recordingRate
    override val analysisRate get() = capture.analysisRate
    override val isRunning get() = capture.isRunning
    override fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = capture.start(file, onError, onFrame)
    override fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = capture.resume(file, onError, onFrame)
    override fun stop() { capture.stop() }
}
