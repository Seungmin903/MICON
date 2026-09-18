package com.micon.app.data.onboarding

import com.micon.app.data.singanalysis.*
import com.micon.app.range.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class VoiceOnboardingInputTest {
    @get:Rule val files = TemporaryFolder()
    private val range = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72,
        emptyList(), "completed", "FOUND", "FOUND", "available")
    private fun audio(seconds: Double = 35.0, rate: Int = 48000) = onboardingRecording(
        File.createTempFile("voice-onboarding", ".wav", files.root), sampleRate = rate, seconds = seconds)
    private fun problem(recording: SavedRecording) = VoiceOnboardingInput.problem("local-draft", range, recording)

    @Test fun validBoundaryLengthsAndBothOriginalSampleRatesAreAccepted() {
        for (rate in listOf(44100, 48000)) for (seconds in listOf(30.0, 35.0, 120.0)) {
            assertNull(problem(audio(seconds, rate)))
        }
    }

    @Test fun insufficientAndExcessiveLengthsAreRejected() {
        for (seconds in listOf(29.99, 120.06, 200.0)) assertNotNull(problem(audio(seconds)))
        val recording = audio()
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNotNull(problem(recording.copy(meta = recording.meta.copy(durationSec = seconds))))
        }
    }

    @Test fun lowBandwidthStereoAndNonPcm16MetadataAreRejected() {
        assertNotNull(problem(audio(rate = 16000)))
        val recording = audio()
        assertNotNull(problem(recording.copy(meta = recording.meta.copy(channels = 2))))
        assertNotNull(problem(recording.copy(meta = recording.meta.copy(bitsPerSample = 32))))
    }

    @Test fun pausedRecordingsNeedThirtySecondsOfValidNonOverlappingSegments() {
        val recording = audio(40.0)
        val valid = listOf(SingTake(0.0, 15.0, 10.0, 25.0), SingTake(20.0, 35.0, 25.0, 40.0))
        assertNull(problem(recording.copy(meta = recording.meta.copy(takes = valid))))
        val invalid = listOf(emptyList(), valid.take(1), valid + valid.last(),
            listOf(SingTake(0.0, 41.0, 0.0, 41.0)), listOf(SingTake(-1.0, 35.0, 0.0, 36.0)),
            listOf(SingTake(0.0, 35.0, 0.0, Double.NaN)), listOf(SingTake(0.0, 35.0, 35.0, 0.0)))
        for (takes in invalid) assertNotNull(problem(recording.copy(meta = recording.meta.copy(takes = takes))))
    }

    @Test fun missingTruncatedAndMismatchedFilesAreRejected() {
        val missing = audio().also { assertTrue(it.wav.delete()) }
        assertNotNull(problem(missing))
        val truncated = audio()
        RandomAccessFile(truncated.wav, "rw").use { it.setLength(44) }
        assertNotNull(problem(truncated))
        val mismatched = audio(40.0)
        assertNotNull(problem(mismatched.copy(meta = mismatched.meta.copy(durationSec = 35.0))))
    }

    @Test fun anAvailableRangeNeedsValidOrderedBounds() {
        val recording = audio()
        for (report in listOf(range.copy(availability = "insufficient_data"), range.copy(lowMidi = null),
            range.copy(highMidi = null), range.copy(lowMidi = 73), range.copy(lowMidi = -1))) {
            assertNotNull(VoiceOnboardingInput.problem("local-draft", report, recording))
        }
        assertNotNull(VoiceOnboardingInput.problem("local-draft", null, recording))
        assertNotNull(VoiceOnboardingInput.problem("another-draft", range, recording))
    }
}
