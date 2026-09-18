package com.micon.app.ui.report

import android.content.Context
import android.content.ContextWrapper
import com.micon.app.audio.PitchCapture
import com.micon.app.range.RangeRepository
import com.micon.app.scoring.ScoringMath
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OnboardingRangeGuideRecordingTest {
    private class Recorder : RangeRecorder {
        override var capturedSeconds = 0.0
        override val recordingRate = 16000
        override val analysisRate = 16000.0
        override var isRunning = false
        var resumes = 0
        var failure = 0
        lateinit var file: File
        lateinit var frames: (PitchCapture.PitchFrame) -> Unit
        override fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) {
            this.file = file; frames = onFrame; isRunning = true
            file.writeBytes(ByteArray(44))
        }
        override fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) {
            assertEquals(this.file, file); resumes++
            if (failure == 1) throw IllegalStateException("open failed")
            if (failure == 2) { onError(IllegalStateException("start failed")); return }
            frames = onFrame; isRunning = true; feed(null)
        }
        override fun stop() { isRunning = false }
        fun feed(midi: Int?) {
            check(isRunning)
            file.appendBytes(ByteArray(640))
            val time = capturedSeconds; capturedSeconds += .02
            frames(PitchCapture.PitchFrame(time, midi?.let { ScoringMath.midiToHz(it.toDouble()) } ?: 0.0,
                if (midi == null) 0.0 else 1.0, if (midi == null) .0001 else .1,
                if (midi == null) .0001 else .1))
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("state did not arrive", condition())
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun fixture(): Pair<GuidedRangeViewModel, Recorder> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "guide-recording-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = folder
        }
        val recorder = Recorder()
        lateinit var vm: GuidedRangeViewModel
        main { vm = GuidedRangeViewModel(isolated, repository = RangeRepository(isolated, deviceOnly = true),
            submitOnFinish = false, awaitAnchorConfirmation = true, capture = recorder) }
        waitFor { vm.state.value.soundReady }
        main { vm.start() }
        repeat(55) { recorder.feed(null); Thread.sleep(2) }
        repeat(101) { if (recorder.isRunning) recorder.feed(60); Thread.sleep(2) }
        waitFor { vm.state.value.pausedForGuide }
        return vm to recorder
    }

    @Test fun guidePausesRecordingAndResumeStartsOneCueOnTheSameFile() {
        val (vm, recorder) = fixture()
        try {
            assertFalse(recorder.isRunning)
            assertEquals("anchor_ready", vm.state.value.session!!.phase)
            assertNull(vm.state.value.session!!.cueMidi)
            val time = recorder.capturedSeconds; val bytes = recorder.file.length()
            Thread.sleep(2200) // The recording watchdog must tolerate guide pauses.
            assertTrue(vm.state.value.running); assertEquals(time, recorder.capturedSeconds, 0.0)
            assertEquals(bytes, recorder.file.length())
            main { vm.startAnchorMeasurement(); vm.startAnchorMeasurement() }
            waitFor { vm.state.value.session?.phase == "anchor" }
            assertEquals(1, recorder.resumes)
            assertEquals(1, vm.state.value.session!!.cueId)
            assertTrue(recorder.file.length() > bytes)
            assertEquals(time + .02, recorder.capturedSeconds, 1e-8)
        } finally {
            main { vm.finish("app_interrupted") }
            waitFor { vm.state.value.finished }
            assertTrue(recorder.file.isFile)
        }
    }

    @Test fun synchronousAndAsynchronousResumeErrorsPreserveAudioAndAllowRetry() {
        val (vm, recorder) = fixture()
        try {
            val original = recorder.file.readBytes()
            for (failure in listOf(1, 2)) {
                recorder.failure = failure
                main { vm.startAnchorMeasurement() }
                waitFor { vm.state.value.error != null && !vm.state.value.resumingGuide }
                assertTrue(vm.state.value.running); assertFalse(vm.state.value.finished)
                assertTrue(vm.state.value.pausedForGuide); assertFalse(recorder.isRunning)
                assertEquals("anchor_ready", vm.state.value.session!!.phase)
                assertEquals(0, vm.state.value.session!!.cueId)
                assertArrayEquals(original, recorder.file.readBytes())
            }
            recorder.failure = 0
            main { vm.startAnchorMeasurement() }
            waitFor { vm.state.value.session?.phase == "anchor" }
            assertEquals(3, recorder.resumes); assertNull(vm.state.value.error)
            assertEquals(1, vm.state.value.session!!.cueId)
        } finally {
            main { vm.finish("app_interrupted") }
            waitFor { vm.state.value.finished }
        }
    }

    @Test fun leavingDuringGuideSavesTheRecordingWithoutStartingTheAnchor() {
        val (vm, recorder) = fixture()
        val bytes = recorder.file.length()
        main { vm.finish("app_interrupted") }
        waitFor { vm.state.value.finished }
        assertFalse(recorder.isRunning); assertEquals(0, recorder.resumes)
        assertEquals(bytes, recorder.file.length()); assertTrue(vm.state.value.hasPending)
        main { vm.startAnchorMeasurement() }
        assertEquals(0, recorder.resumes)
    }
}
