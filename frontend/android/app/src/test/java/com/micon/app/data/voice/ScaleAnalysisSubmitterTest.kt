package com.micon.app.data.voice

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class ScaleAnalysisSubmitterTest {
    private class FakeVoice : VoiceAnalysisRepository {
        var uploads = 0; var creates = 0
        var uploadError: Exception? = null; var createError: Exception? = null
        var lastMethod: String? = null; var lastSongId: Long? = 1
        override suspend fun upload(file: File, mimeType: String, attemptNo: Int, onProgress: (Float) -> Unit): Long {
            uploads++; uploadError?.let { throw it }; onProgress(1f); return 57
        }
        override suspend fun create(recordingId: Long, songId: Long?, method: String): VoiceAnalysis {
            creates++; lastMethod = method; lastSongId = songId; createError?.let { throw it }
            return VoiceAnalysis(9, recordingId, songId, method, VoiceAnalysisStatus.READY, null, null, null, null, null)
        }
        override suspend fun get(analysisId: Long) = throw UnsupportedOperationException()
        override suspend fun latest(): VoiceAnalysis? = null
        override suspend fun report(analysisId: Long) = throw UnsupportedOperationException()
    }

    private val voice = FakeVoice()
    private var loggedIn = true
    private val submitter = ScaleAnalysisSubmitter(voice) { loggedIn }
    private lateinit var wav: File

    @Before fun setUp() { wav = File.createTempFile("quick", ".wav").apply { writeBytes(ByteArray(44 + 16_000 * 2 * 5)) } }
    @After fun tearDown() { wav.delete() }

    @Test fun `guest does not call the server`() = runBlocking {
        loggedIn = false
        assertTrue(submitter.submit(wav) is ScaleSubmitResult.Unavailable)
        assertEquals(0, voice.uploads); assertEquals(0, voice.creates)
    }

    @Test fun `upload then SCALE create without songId`() = runBlocking {
        val messages = ArrayList<String>()
        val result = submitter.submit(wav) { messages += it } as ScaleSubmitResult.Accepted
        assertEquals(9L, result.analysisId); assertEquals(57L, result.recordingId)
        assertEquals("SCALE", voice.lastMethod); assertEquals(null, voice.lastSongId)
        assertTrue(messages.any { it.contains("100%") })
    }

    @Test fun `ai disabled 503 is unavailable, network is failed`() = runBlocking {
        voice.createError = VoiceApiException(503, "VOICE_ANALYSIS_DISPATCH_FAILED", "음성 분석 작업을 요청하지 못했습니다.")
        assertTrue(submitter.submit(wav) is ScaleSubmitResult.Unavailable)
        voice.createError = null; voice.uploadError = IOException("timeout")
        assertTrue(submitter.submit(wav) is ScaleSubmitResult.Failed)
    }

    @Test fun `empty wav is rejected before upload`() = runBlocking {
        val empty = File.createTempFile("empty", ".wav").apply { writeBytes(ByteArray(44)) }
        try {
            assertTrue(submitter.submit(empty) is ScaleSubmitResult.Failed)
            assertEquals(0, voice.uploads)
        } finally { empty.delete() }
    }
}
