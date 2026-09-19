package com.micon.app.range

import com.micon.app.audio.writeQualityWav
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class RangeInputQualityTest {
    @get:Rule val files = TemporaryFolder()
    private val submission = usableRangeSubmission()
    private fun inspect(s: RangeSubmission = submission, amplitude: Double = .08): RangeInputCheck {
        val file = files.newFile()
        writeQualityWav(file, amplitude = amplitude)
        return RangeInputQuality().inspect(s, file, 16000, true)
    }

    @Test fun modelMinimumIsOneValidSealedSuccessAndNoInventedNoteCount() {
        assertTrue(inspect().ready)
        assertEquals(1, inspect().usableNotes)
        assertFalse(inspect(submission.copy(sampling = submission.sampling!!.copy(basicCompleted = false))).ready)
        assertFalse(inspect(submission.copy(attempts = emptyList())).ready)
    }

    @Test fun skipsFailedScoresAndLiveCheckpointsCannotFinish() {
        val a = submission.attempts.single()
        val skipped = a.copy(clientStatus = "unmeasured", metrics = null, evaluationStart = null, evaluationEnd = null,
            referenceStart = null, referenceEnd = null, referenceMidi = null, runEndReason = null,
            progressQualified = false, advanceReason = "manual", searchOutcome = "manual_failed")
        assertFalse(inspect(submission.copy(attempts = listOf(skipped))).ready)
        val failed = a.copy(clientStatus = "not_passed", metrics = a.metrics!!.copy(creditedHoldSeconds = .5,
            continuousHoldSeconds = .5, outsideSeconds = .7, excursionPenalty = 20.0), progressQualified = false)
        assertFalse(inspect(submission.copy(attempts = listOf(failed))).ready)
        assertFalse(inspect(submission.copy(attempts = listOf(a.copy(runEndReason = "checkpoint")))).ready)
        assertFalse(inspect(amplitude = 0.0).ready)
    }

    @Test fun missingNonfiniteAndInconsistentModelEvidenceCannotFinish() {
        val a = submission.attempts.single()
        for (bad in listOf(a.copy(referenceStart = null), a.copy(evaluationEnd = 5.0),
            a.copy(metrics = a.metrics!!.copy(referenceRmsCents = null)),
            a.copy(metrics = a.metrics!!.copy(creditedHoldSeconds = Double.NaN)),
            a.copy(metrics = a.metrics!!.copy(excursionPenalty = 1.0)), a.copy(probeStage = null))) {
            assertFalse(inspect(submission.copy(attempts = listOf(bad))).ready)
        }
    }

    @Test fun semitoneReferenceIsInclusiveAndLargerOffsetsAreRejectedForAllBufferedVersions() {
        val a = submission.attempts.single()
        for (version in listOf("guided-range-v5", "guided-range-v6", "guided-range-v7")) {
            for (offset in listOf(-2.0, -1.001, -1.0, 1.0, 1.001, 1.782956925381555, 2.0)) {
                val shifted = submission.copy(protocolVersion = version,
                    sampling = submission.sampling!!.copy(strategy = "adaptive_8", coarseStep = 8),
                    attempts = listOf(a.copy(referenceMidi = 60 + offset)))
                assertEquals("$version offset=$offset", kotlin.math.abs(offset) <= 1.0, inspect(shifted).ready)
            }
        }
    }

    @Test fun failedAttemptOutsideSemitoneGateBlocksWholeSubmissionEvenWithAValidSuccess() {
        val a = submission.attempts.single()
        val failed = a.copy(attemptId = "failed", visitId = "failed-visit", targetMidi = 49, direction = "down",
            cueStart = 2.6, cueEnd = 2.65, responseStart = 2.9, responseEnd = 3.36,
            referenceStart = 2.9, referenceEnd = 3.1, referenceMidi = 50.782956925381555,
            evaluationStart = 3.1, evaluationEnd = 3.36, clientStatus = "not_passed",
            metrics = a.metrics!!.copy(creditedHoldSeconds = .26, continuousHoldSeconds = .26),
            probeStage = "coarse", progressQualified = false, advanceReason = "manual", searchOutcome = "manual_failed")
        val s = submission.copy(attempts = listOf(a, failed))
        assertTrue(inspect(s.copy(attempts = listOf(a, failed.copy(referenceMidi = 50.0)))).ready)
        val result = inspect(s)
        assertFalse(result.ready)
        assertFalse(result.canSupplement)
    }

    @Test fun finalizedTruncatedCorruptAndMissingWavsAreRejected() {
        val file = files.newFile()
        writeQualityWav(file)
        RandomAccessFile(file, "rw").use { it.setLength(44 + 16000) }
        assertFalse(RangeInputQuality().inspect(submission, file, 16000, true).ready)
        writeQualityWav(file)
        RandomAccessFile(file, "rw").use { it.seek(0); it.writeInt(0) }
        assertFalse(RangeInputQuality().inspect(submission, file, 16000, true).ready)
        file.delete()
        assertFalse(RangeInputQuality().inspect(submission, file, 16000, true).ready)
    }
}
