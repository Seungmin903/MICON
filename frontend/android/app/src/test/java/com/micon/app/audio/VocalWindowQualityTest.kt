package com.micon.app.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class VocalWindowQualityTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun completedWindowsAreCachedAndFourAreRequiredAtBothNativeRates() {
        for (rate in listOf(44100, 48000)) {
            val file = files.newFile("$rate.wav")
            val checker = VocalWindowQuality()
            writeQualityWav(file, rate, 4.0)
            assertEquals(1, checker.inspect(file, rate).usableWindows)
            assertFalse(checker.inspect(file, rate).ready)
            writeQualityWav(file, rate, 8.0)
            assertEquals(3, checker.inspect(file, rate).usableWindows)
            assertFalse(checker.inspect(file, rate).ready)
            writeQualityWav(file, rate, 10.0)
            val ready = checker.inspect(file, rate, true)
            assertTrue(ready.ready)
            assertEquals(4, ready.usableWindows)
            assertEquals(ready, checker.inspect(file, rate, true))
            writeQualityWav(file, rate, 4.0, amplitude = 0.0)
            assertEquals(0, checker.inspect(file, rate).usableWindows)
        }
    }

    @Test fun silenceLowLevelSparseAndClippedAudioCannotQualify() {
        for (rate in listOf(44100, 48000)) {
            assertFalse(VocalWindowQuality.usable(FloatArray(4 * rate), rate))
            assertFalse(VocalWindowQuality.usable(FloatArray(4 * rate) { .004f }, rate))
            assertFalse(VocalWindowQuality.usable(FloatArray(4 * rate) { if (it < rate) .1f else 0f }, rate))
            assertFalse(VocalWindowQuality.usable(FloatArray(4 * rate) { if (it < rate / 10) 1f else .1f }, rate))
            assertTrue(VocalWindowQuality.usable(FloatArray(4 * rate) { .01f }, rate))
        }
    }

    @Test fun incompleteWindowsAndMalformedFinalFilesAreRejected() {
        val file = files.newFile("final.wav")
        writeQualityWav(file, 44100, 9.99)
        assertFalse(VocalWindowQuality().inspect(file, 44100, true).ready)
        writeQualityWav(file, 44100, 10.0)
        RandomAccessFile(file, "rw").use { it.seek(22); it.write(byteArrayOf(2, 0)) }
        assertTrue(VocalWindowQuality().inspect(file, 44100, true).fileProblem)
        assertTrue(VocalWindowQuality().inspect(file, 16000, true).fileProblem)
    }

    @Test fun pausedRecordingRetainsWindowsAndAppendingDoesNotCountThemTwice() {
        val file = files.newFile("resume.wav")
        val checker = VocalWindowQuality()
        writeQualityWav(file, 48000, 12.0)
        assertEquals(5, checker.inspect(file, 48000, true).usableWindows)
        writeQualityWav(file, 48000, 16.0)
        assertEquals(7, checker.inspect(file, 48000, true).usableWindows)
        assertEquals(7, checker.inspect(file, 48000, true).checkedWindows)
    }

    @Test fun lostAudioCannotReuseAnEarlierReadyResult() {
        val file = files.newFile("lost.wav")
        val checker = VocalWindowQuality()
        writeQualityWav(file, 44100, 12.0)
        assertTrue(checker.inspect(file, 44100).ready)
        RandomAccessFile(file, "rw").use { it.setLength(44) }
        assertFalse(checker.inspect(file, 44100).ready)
        assertFalse(checker.inspect(file, 44100, true).ready)
    }
}
