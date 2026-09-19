package com.micon.app.ui.report

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.audio.PitchCapture
import com.micon.app.range.RangeRepository
import com.micon.app.scoring.ScoringMath
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OnboardingRangePreparationRecordingTest {
    private class Recorder : RangeRecorder {
        override var capturedSeconds = 0.0
        override val recordingRate = 16000
        override val analysisRate = 16000.0
        override var isRunning = false
        var starts = 0
        var resumes = 0
        var failure = 0
        lateinit var file: File
        lateinit var frames: (PitchCapture.PitchFrame) -> Unit
        override fun start(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) {
            starts++
            if (failure == 3) throw IllegalStateException("open failed")
            this.file = file; frames = onFrame; isRunning = true; file.writeBytes(ByteArray(44))
        }
        override fun resume(file: File, onError: (Throwable) -> Unit, onFrame: (PitchCapture.PitchFrame) -> Unit) {
            assertEquals(this.file, file); resumes++
            if (failure == 1) throw IllegalStateException("resume failed")
            if (failure == 2) { onError(IllegalStateException("capture failed")); return }
            frames = onFrame; isRunning = true; feed(null)
        }
        override fun stop() { isRunning = false }
        fun feed(midi: Int?) {
            check(isRunning)
            file.appendBytes(ByteArray(640))
            val t = capturedSeconds; capturedSeconds += .02
            frames(PitchCapture.PitchFrame(t, midi?.let { ScoringMath.midiToHz(it.toDouble()) } ?: 0.0,
                if (midi == null) 0.0 else 1.0, if (midi == null) .0001 else .1,
                if (midi == null) .0001 else .1))
        }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("state did not arrive", condition())
    }
    private fun fixture(failure: Int = 0): Pair<GuidedRangeViewModel, Recorder> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "preparation-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = dir
        }
        val recorder = Recorder().apply { this.failure = failure }
        lateinit var vm: GuidedRangeViewModel
        main { vm = GuidedRangeViewModel(context, repository = RangeRepository(context, deviceOnly = true),
            submitOnFinish = false, awaitAnchorConfirmation = true, capture = recorder, awaitNoiseConfirmation = true) }
        waitFor { vm.state.value.soundReady }
        return vm to recorder
    }
    private fun quiet(vm: GuidedRangeViewModel, recorder: Recorder) {
        repeat(55) { if (recorder.isRunning) recorder.feed(null); Thread.sleep(2) }
        waitFor { vm.state.value.pausedForIntro }
    }
    private fun finish(vm: GuidedRangeViewModel) {
        main { vm.finish("app_interrupted") }
        waitFor { vm.state.value.finished }
    }

    @Test fun automaticCalibrationStartsOnceAndComfortablePitchWaitsForExplicitResume() {
        val (vm, recorder) = fixture()
        main { assertTrue(vm.claimAutomaticPreparation()); assertFalse(vm.claimAutomaticPreparation()) }
        main { vm.startAutomaticPreparation(); vm.startAutomaticPreparation() }
        assertEquals(1, recorder.starts)
        quiet(vm, recorder)
        try {
            val time = recorder.capturedSeconds; val bytes = recorder.file.length()
            Thread.sleep(2200)
            assertFalse(recorder.isRunning); assertTrue(vm.state.value.running)
            assertEquals("comfortable_ready", vm.state.value.session!!.phase)
            assertEquals(time, recorder.capturedSeconds, 0.0); assertEquals(bytes, recorder.file.length())
            assertNull(vm.state.value.session!!.cueMidi)
            main { vm.startComfortableMeasurement(); vm.startComfortableMeasurement() }
            waitFor { vm.state.value.session?.phase == "comfortable" }
            assertEquals(1, recorder.resumes); assertEquals(time + .02, recorder.capturedSeconds, 1e-8)
            repeat(105) { if (recorder.isRunning) recorder.feed(60); Thread.sleep(2) }
            waitFor { vm.state.value.pausedForGuide }
            assertFalse(recorder.isRunning); assertNull(vm.state.value.session!!.cueMidi)
        } finally { finish(vm) }
    }

    @Test fun resumeFailuresKeepTheSameAudioAndAllowRetry() {
        val (vm, recorder) = fixture()
        main { vm.startAutomaticPreparation() }; quiet(vm, recorder)
        try {
            val bytes = recorder.file.length()
            for (failure in 1..2) {
                recorder.failure = failure
                main { vm.startComfortableMeasurement() }
                waitFor { vm.state.value.error != null && !vm.state.value.resumingIntro }
                assertTrue(vm.state.value.pausedForIntro); assertFalse(vm.state.value.introLeaving)
                assertEquals(bytes, recorder.file.length()); assertFalse(recorder.isRunning)
            }
            recorder.failure = 0
            main { vm.startComfortableMeasurement() }
            waitFor { vm.state.value.session?.phase == "comfortable" }
            assertEquals(3, recorder.resumes); assertNull(vm.state.value.error)
        } finally { finish(vm) }
    }

    @Test fun initialOpenFailureCanRetryAndLeavingTheIntroPreservesAudio() {
        val (vm, recorder) = fixture(failure = 3)
        main { vm.startAutomaticPreparation() }
        assertFalse(vm.state.value.running); assertNotNull(vm.state.value.error)
        recorder.failure = 0
        main { vm.startAutomaticPreparation() }; quiet(vm, recorder)
        val bytes = recorder.file.length()
        finish(vm)
        assertEquals(2, recorder.starts); assertEquals(bytes, recorder.file.length())
        assertTrue(vm.state.value.hasPending); assertFalse(recorder.isRunning)
        main { vm.startComfortableMeasurement() }
        assertEquals(0, recorder.resumes)
    }
}
