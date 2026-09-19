package com.micon.app.audio

import java.io.File

interface PcmRecorder {
    val capturedSeconds: Double
    val recordingRate: Int
    val analysisRate: Double
    val isRunning: Boolean
    fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit)
    fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit)
    fun stop()
}

internal class MicrophoneTimbreRecorder : PcmRecorder {
    private val capture = PitchCapture(analyzePitch = false, timbreRecording = true)
    override val capturedSeconds get() = capture.capturedSeconds
    override val recordingRate get() = capture.recordingRate
    override val analysisRate get() = capture.analysisRate
    override val isRunning get() = capture.isRunning
    override fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = capture.start(file, onError, onFrame)
    override fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) = capture.resume(file, onError, onFrame)
    override fun stop() { capture.stop() }
}
