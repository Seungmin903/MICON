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
        override suspend fun analyze(audio: MultipartBody.Part, metadata: RequestBody): RangeReport {
            requests++
            failure?.let { throw it }
            return report
        }
    }

    private fun pending(repository: RangeRepository): String {
        val id = UUID.randomUUID().toString()
        repository.audio(id).writeBytes(ByteArray(44 + 32000))
        repository.savePending(PendingRange(RangeSession(id).stop()))
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

    companion object {
        private fun result() = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(60, "passed")), 60, 60,
            listOf(listOf(60, 60)), "user_stopped", "not_probed", "not_probed", "available",
            verification = "model_verified")
    }
}
