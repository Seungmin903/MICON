package com.micon.app.data.holo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUploadDrainTest {
    @Test fun `completed stream is used without fallback`() = runTest {
        val upload = CompletableDeferred<String?>().apply { complete("ready") }
        assertEquals(StreamUploadDrain.Ready("ready"), drainStreamUpload(upload, 15_000))
    }

    @Test fun `stalled stream is cancelled so WAV fallback can run`() = runTest {
        val upload = async<String?> { awaitCancellation() }
        assertEquals(StreamUploadDrain.Fallback("timeout"), drainStreamUpload(upload, 15_000))
        assertTrue(upload.isCancelled)
    }

    @Test fun `unsupported stream falls back immediately`() = runTest {
        val upload = CompletableDeferred<String?>().apply { complete(null) }
        assertEquals(StreamUploadDrain.Fallback("unavailable"), drainStreamUpload(upload, 15_000))
    }
}
