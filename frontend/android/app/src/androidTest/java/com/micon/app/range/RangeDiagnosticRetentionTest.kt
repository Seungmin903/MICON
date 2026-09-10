package com.micon.app.range

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Isolated app files and a different token: never consumes the user's armed diagnostic session. */
@RunWith(AndroidJUnit4::class)
class RangeDiagnosticRetentionTest {
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val label = "diagnostic-test-${UUID.randomUUID()}"
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(base.cacheDir, label).apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$label-$name", mode)
        }
    }

    @Test fun tokenIsConsumedOnceAndOtherRecordingsAreNotRetained() {
        val context = isolatedContext()
        val id = UUID.randomUUID().toString()
        val repository = RangeRepository(context)
        val audio = repository.audio(id).apply { writeBytes(ByteArray(100)) }
        val diagnostic = RangeDiagnostics.claim(context, id, audio, "test-once")!!
        diagnostic.trace.record("evaluated", mapOf("audioTime" to .5))
        diagnostic.finish(RangeSession(id).stop(), 16000)
        assertNull(RangeDiagnostics.claim(context, UUID.randomUUID().toString(), audio, "test-once"))
        assertFalse(RangeDiagnostics.armed(context, "test-once"))
        assertTrue(RangeDiagnostics.retainsAudio(context, id))
        repository.savePending(PendingRange(RangeSession(id).stop()))
        repository.discardPending()
        assertTrue(audio.isFile)
        val otherId = UUID.randomUUID().toString()
        val other = repository.audio(otherId).apply { writeBytes(ByteArray(100)) }
        repository.savePending(PendingRange(RangeSession(otherId).stop()))
        repository.discardPending()
        assertFalse(other.exists())
        assertFalse(RangeDiagnostics.retainsAudio(context, otherId))
    }

    @Test fun realModelUploadKeepsOnlyTheAuthorizedDiagnosticAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rangeModelPreview") == "true")
        val context = isolatedContext()
        val repository = RangeRepository(context)
        for (retain in listOf(true, false)) {
            val id = UUID.randomUUID().toString()
            val audio = repository.audio(id)
            val rate = 16000
            val samples = 36800 // 2.3 s, first second quiet, then 1.3 s of C4.
            val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(samples * 2 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(samples * 2)
            val hz = 440 * 2.0.pow((60 - 69.0) / 12)
            repeat(samples) { i ->
                val t = i.toDouble() / rate
                val x = if (t < 1) 0.0 else .25 * sin(2 * PI * hz * t) + .08 * sin(4 * PI * hz * t) + .04 * sin(6 * PI * hz * t)
                wav.putShort((x * 32767).toInt().toShort())
            }
            audio.writeBytes(wav.array())
            val submission = RangeSubmission(id, protocolVersion = "guided-range-v4", startMidi = 60, noiseRms = .0001, durationSeconds = 2.3,
                stopReason = "user_stopped", lowerBoundaryStatus = "not_probed", upperBoundaryStatus = "user_stopped",
                registerEvents = emptyList(), attempts = listOf(RangeAttempt("attempt-1", 60, "anchor", 0.0, .5,
                    .75, 2.3, 1.2, 2.2, "passed", null, visitId = "visit-1", advanceReason = "threshold",
                    progressQualified = true, searchOutcome = "reached")))
            if (retain) RangeDiagnostics.claim(context, id, audio, "test-model")!!.finish(submission, rate)
            repository.savePending(PendingRange(submission))
            val report = repository.submit { }
            assertEquals("model_verified", report.verification)
            assertEquals("passed", report.notes.first { it.midi == 60 }.status)
            assertEquals(retain, audio.exists())
            assertNull(repository.pending())
            if (retain) {
                assertTrue(File(context.filesDir, "range-diagnostics/$id/submission.json").isFile)
                assertTrue(File(context.filesDir, "range-diagnostics/$id/trace.ndjson").readText().contains("trace_closed"))
            }
        }
    }
}
