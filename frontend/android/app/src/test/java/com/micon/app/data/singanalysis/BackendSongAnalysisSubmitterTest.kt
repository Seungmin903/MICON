package com.micon.app.data.singanalysis

import com.micon.app.data.voice.VoiceAnalysis
import com.micon.app.data.voice.VoiceAnalysisReport
import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceAnalysisStatus
import com.micon.app.data.voice.VoiceApiException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class BackendSongAnalysisSubmitterTest {
    /** 호출을 기록하고 정해진 답을 돌려주는 가짜 저장소. */
    private class FakeVoice : VoiceAnalysisRepository {
        var uploads = 0
        var creates = 0
        var uploadError: Exception? = null
        var createError: Exception? = null
        var nextRecordingId = 57L
        var nextAnalysisId = 9L
        var lastCreate: Triple<Long, Long?, String>? = null

        override suspend fun upload(file: File, mimeType: String, attemptNo: Int, onProgress: (Float) -> Unit): Long {
            uploads++
            uploadError?.let { throw it }
            onProgress(0.5f); onProgress(1f)
            return nextRecordingId
        }

        override suspend fun create(recordingId: Long, songId: Long?, method: String): VoiceAnalysis {
            creates++
            lastCreate = Triple(recordingId, songId, method)
            createError?.let { throw it }
            return analysis(nextAnalysisId, VoiceAnalysisStatus.READY)
        }

        override suspend fun get(analysisId: Long) = analysis(analysisId, VoiceAnalysisStatus.ANALYZING)
        override suspend fun latest(): VoiceAnalysis? = null
        override suspend fun report(analysisId: Long) = VoiceAnalysisReport(analysis(analysisId, VoiceAnalysisStatus.COMPLETED), null, null)

        private fun analysis(id: Long, status: VoiceAnalysisStatus) =
            VoiceAnalysis(id, nextRecordingId, 42, "SONG", status, null, null, null, "2026-09-13T21:00:00", null)
    }

    private lateinit var wav: File
    private val voice = FakeVoice()
    private val persisted = ArrayList<SingRecordingMeta>()
    private var loggedIn = true
    private val submitter = BackendSongAnalysisSubmitter(voice, isLoggedIn = { loggedIn }, persist = { persisted += it })

    @Before fun setUp() {
        wav = File.createTempFile("sing", ".wav").apply { writeBytes(ByteArray(44 + 16_000 * 2 * 3)) }
    }

    @After fun tearDown() { wav.delete() }

    private fun recording(serverRecordingId: Long? = null, serverAnalysisId: Long? = null) = SavedRecording(
        SingRecordingMeta(
            id = "sing-1-42", songId = 42, title = "밤편지", artist = "아이유", scope = SingScope.FULL.name, planSource = PlanSource.SONG_START.name,
            plannedStartSec = 0.0, plannedEndSec = 200.0, sungStartSec = 0.0, sungEndSec = 3.0, endedEarly = true, recordedAt = 1L,
            sampleRate = 16_000, durationSec = 3.0, lyricTiming = "NONE", wavFileName = wav.name,
            serverRecordingId = serverRecordingId, serverAnalysisId = serverAnalysisId,
        ),
        wav, File(wav.parentFile, "sing-1-42.json"),
    )

    @Test fun `guest does not touch the server`() = runBlocking {
        loggedIn = false
        val result = submitter.submit(recording())
        assertTrue(result is SubmitResult.Unavailable)
        assertEquals(0, voice.uploads); assertEquals(0, voice.creates)
        assertTrue(persisted.isEmpty())
    }

    @Test fun `upload then create persists both server ids and reports progress`() = runBlocking {
        val messages = ArrayList<String>()
        val result = submitter.submit(recording()) { messages += it }

        val accepted = result as SubmitResult.Accepted
        assertEquals(9L, accepted.analysisId)
        assertEquals(1, voice.uploads); assertEquals(1, voice.creates)
        assertEquals(Triple(57L, 42L, "SONG"), voice.lastCreate)
        assertEquals(listOf(57L, 57L), persisted.map { it.serverRecordingId })
        assertEquals(listOf(null, 9L), persisted.map { it.serverAnalysisId })
        assertTrue(messages.any { it.contains("100%") })
        assertTrue(messages.last().contains("분석을 요청"))
    }

    @Test fun `already uploaded recording skips upload`() = runBlocking {
        val result = submitter.submit(recording(serverRecordingId = 57))
        assertTrue(result is SubmitResult.Accepted)
        assertEquals(0, voice.uploads); assertEquals(1, voice.creates)
        assertEquals(57L, voice.lastCreate!!.first)
    }

    @Test fun `ai disabled 503 is unavailable but keeps the uploaded recording id`() = runBlocking {
        voice.createError = VoiceApiException(503, "VOICE_ANALYSIS_DISPATCH_FAILED", "음성 분석 작업을 요청하지 못했습니다.")
        val result = submitter.submit(recording())
        assertTrue(result is SubmitResult.Unavailable)
        assertTrue((result as SubmitResult.Unavailable).message.contains("준비"))
        assertEquals(57L, persisted.single().serverRecordingId)
        assertNull(persisted.single().serverAnalysisId)
    }

    @Test fun `recording missing on server clears the stale id so next submit re-uploads`() = runBlocking {
        voice.createError = VoiceApiException(404, "RECORDING_NOT_FOUND", "녹음 파일을 찾을 수 없습니다.")
        val result = submitter.submit(recording(serverRecordingId = 57, serverAnalysisId = 3))
        assertTrue(result is SubmitResult.Failed)
        assertEquals(0, voice.uploads)
        assertNull(persisted.single().serverRecordingId)
        assertNull(persisted.single().serverAnalysisId)
    }

    @Test fun `in progress conflict is treated as accepted with the known analysis id`() = runBlocking {
        voice.createError = VoiceApiException(409, "VOICE_ANALYSIS_IN_PROGRESS", "해당 녹음의 분석이 이미 진행 중입니다.")
        val result = submitter.submit(recording(serverRecordingId = 57, serverAnalysisId = 3)) as SubmitResult.Accepted
        assertEquals(3L, result.analysisId)
    }

    @Test fun `network failure during upload is a retryable failure without persisting`() = runBlocking {
        voice.uploadError = IOException("timeout")
        val result = submitter.submit(recording())
        assertTrue(result is SubmitResult.Failed)
        assertTrue(persisted.isEmpty()); assertEquals(0, voice.creates)
    }

    @Test fun `oversized wav is rejected before upload`() = runBlocking {
        val big = File.createTempFile("big", ".wav")
        try {
            java.io.RandomAccessFile(big, "rw").use { it.setLength(BackendSongAnalysisSubmitter.MAX_UPLOAD_BYTES + 1) }
            val saved = recording().let { it.copy(wav = big) }
            val result = submitter.submit(saved)
            assertTrue(result is SubmitResult.Failed)
            assertTrue((result as SubmitResult.Failed).message.contains("25 MB"))
            assertEquals(0, voice.uploads)
        } finally { big.delete() }
    }
}
