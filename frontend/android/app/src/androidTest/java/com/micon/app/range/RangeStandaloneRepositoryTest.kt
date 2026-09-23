package com.micon.app.range

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses isolated app files and a model transport; no account or Spring client is constructed. */
class RangeStandaloneRepositoryTest {
    private fun context(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val label = "range-storage-test-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            override fun getFilesDir() = File(base.cacheDir, label).apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$label-$name", mode)
        }
    }

    private class Model : RangePreviewClient.Api {
        var failure: Exception? = null
        var report = result()
        var requests = 0
        var beforeResult: (() -> Unit)? = null
        override suspend fun analyze(audio: MultipartBody.Part, metadata: RequestBody): RangeReport {
            requests++
            failure?.let { throw it }
            beforeResult?.invoke()
            return report
        }
    }

    private fun pending(repository: RangeRepository): String {
        val id = UUID.randomUUID().toString()
        com.micon.app.audio.writeQualityWav(repository.audio(id))
        repository.savePending(PendingRange(usableRangeSubmission().copy(sessionId = id)))
        return id
    }

    @Test fun connectionFailureSurvivesRecreationAndCanBeRetried() = runBlocking {
        val context = context()
        val model = Model().apply { failure = IOException("disconnected") }
        val repository = RangeRepository(context, model)
        val id = pending(repository)
        assertTrue(runCatching { repository.submit {} }.exceptionOrNull() is IOException)
        assertTrue(repository.audio(id).isFile)
        assertNull(repository.localReport())
        val reopened = RangeRepository(context, model)
        assertEquals(id, reopened.pending()!!.submission.sessionId)
        model.failure = null
        model.report = result().copy(analysisId = id)
        assertEquals("model_verified", reopened.submit {}.verification)
        assertEquals(2, model.requests)
        assertNull(reopened.pending())
        assertEquals(id, RangeRepository(context, model).localReport()!!.analysisId)
        assertFalse(reopened.audio(id).exists())
    }

    @Test fun partialResultDoesNotReplacePreviousCompleteReport() = runBlocking {
        val context = context()
        val model = Model()
        val repository = RangeRepository(context, model)
        val first = pending(repository)
        model.report = result().copy(analysisId = first)
        repository.submit {}
        val second = pending(repository)
        model.report = result().copy(analysisId = second, availability = "insufficient_data", notes = emptyList(), lowMidi = null, highMidi = null)
        repository.submit {}
        val reopened = RangeRepository(context, model)
        assertEquals(first, reopened.localReport()!!.analysisId)
        assertEquals(second, reopened.lastSessionReport()!!.analysisId)
    }

    @Test fun restartingFreeMeasurementRemovesOnlyUnfinishedAudioAndKeepsVerifiedReport() = runBlocking {
        val context = context()
        val model = Model()
        val repository = RangeRepository(context, model)
        val verifiedId = pending(repository)
        model.report = result().copy(analysisId = verifiedId)
        repository.submit {}

        val pendingId = pending(repository)
        val checkpointId = UUID.randomUUID().toString()
        com.micon.app.audio.writeQualityWav(repository.audio(checkpointId))
        repository.saveFreeCheckpoint(FreeRangeCheckpoint(checkpointId, emptyList(), .001, 3.0))

        repository.discardUnfinishedFreeMeasurement()

        assertNull(repository.pending())
        assertNull(repository.freeCheckpoint())
        assertFalse(repository.audio(pendingId).exists())
        assertFalse(repository.audio(checkpointId).exists())
        assertEquals(verifiedId, repository.localReport()?.analysisId)
        assertEquals(verifiedId, repository.lastSessionReport()?.analysisId)
    }

    @Test fun unverifiedOrWrongVersionResponsesPreserveRecordingAndPendingState() = runBlocking {
        val model = Model()
        val repository = RangeRepository(context(), model)
        val id = pending(repository)
        for (response in listOf(result().copy(verification = "client_only"), result().copy(protocolVersion = "guided-range-v1"))) {
            model.report = response
            assertTrue(runCatching { repository.submit {} }.isFailure)
            assertTrue(repository.audio(id).isFile)
            assertEquals(id, repository.pending()!!.submission.sessionId)
            assertNull(repository.localReport())
        }
    }

    @Test fun keepOriginalRetainsSuccessfulRecording() = runBlocking {
        val model = Model()
        val repository = RangeRepository(context(), model)
        val id = pending(repository)
        val saved = repository.pending()!!
        repository.savePending(saved.copy(submission = saved.submission.copy(keepOriginal = true)))
        repository.submit {}
        assertTrue(repository.audio(id).isFile)
        assertNull(repository.pending())
    }

    @Test fun lateModelResultCannotOverwriteOrDeleteANewerPendingMeasurement() = runBlocking {
        val model = Model()
        val repository = RangeRepository(context(), model)
        val oldId = pending(repository)
        var newId: String? = null
        model.beforeResult = {
            repository.clear()
            newId = pending(repository)
        }
        assertTrue(runCatching { repository.submit {} }.isFailure)
        assertNotEquals(oldId, newId)
        assertEquals(newId, repository.pending()!!.submission.sessionId)
        assertTrue(repository.audio(checkNotNull(newId)).isFile)
        assertNull(repository.localReport())
        assertNull(repository.lastSessionReport())
    }

    @Test fun completedTargetsSurviveRecreationAndResetForANewMeasurement() = runBlocking {
        val context = context()
        val model = Model()
        val repository = RangeRepository(context, model)
        val id = pending(repository)
        val first = repository.pending()!!
        fun skipped(target: Int, reason: String, start: Double) = RangeAttempt("visit-$target", target,
            if (reason == "anchor_reset") "anchor" else "up", start, start + .05, start + .05, start + .05,
            null, null, "unmeasured", null, visitId = "visit-$target", selectedForVisit = true, advanceReason = reason,
            searchOutcome = if (reason == "manual") "manual_failed" else null,
            probeStage = if (reason == "anchor_reset") "anchor" else "coarse", anchorMidi = 60)
        fun passed(target: Int, direction: String = "anchor") = first.submission.attempts.single().copy(
            targetMidi = target, direction = direction, referenceMidi = target.toDouble(),
            probeStage = if (direction == "anchor") "anchor" else "refinement",
            anchorMidi = if (direction == "anchor") target else 60,
            metrics = first.submission.attempts.single().metrics!!.copy(actualMidi = target.toDouble(),
                creditedMidi = target, referenceMidi = target.toDouble()))
        repository.savePending(first.copy(submission = first.submission.copy(
            attempts = listOf(passed(60), skipped(66, "manual", 2.6), skipped(63, "manual", 2.7), skipped(62, "anchor_reset", 2.8)))))
        model.report = result().copy(analysisId = id, notes = listOf(RangeNote(61, "passed")))
        repository.submit {}
        var reopened = RangeRepository(context, model)
        assertEquals(setOf(60, 66, 63), reopened.completedTargets(reopened.localReport()!!))
        assertNull(reopened.completedTargets(model.report.copy(analysisId = "other-account-session")))

        val extensionId = pending(reopened)
        val extension = reopened.pending()!!
        reopened.savePending(extension.copy(submission = extension.submission.copy(mode = "extension",
            parentAnalysisId = id, attempts = listOf(passed(65, "extension")))))
        model.report = result().copy(analysisId = extensionId)
        reopened.submit {}
        reopened = RangeRepository(context, model)
        assertEquals(setOf(60, 63, 65, 66), reopened.completedTargets(reopened.localReport()!!))

        val newId = pending(reopened)
        val fresh = reopened.pending()!!
        reopened.savePending(fresh.copy(submission = fresh.submission.copy(startMidi = 70, attempts = listOf(passed(70)))))
        model.report = result().copy(analysisId = newId)
        reopened.submit {}
        assertEquals(setOf(70), RangeRepository(context, model).completedTargets(model.report))
        reopened.clear()
        assertNull(reopened.completedTargets(model.report))
    }

    companion object {
        private fun result() = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(60, "passed")), 60, 60,
            listOf(listOf(60, 60)), "user_stopped", "not_probed", "not_probed", "available",
            verification = "model_verified")
    }
}
