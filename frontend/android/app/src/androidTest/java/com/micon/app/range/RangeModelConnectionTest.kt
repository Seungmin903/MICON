package com.micon.app.range

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.BuildConfig
import com.micon.app.core.network.Json
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Opt-in phone -> USB -> actual CREPE/pYIN -> Retrofit contract check; does not record the microphone. */
@RunWith(AndroidJUnit4::class)
class RangeModelConnectionTest {
    @Test fun modelAnalysisWithoutLogin() = modelAnalysis("guided-range-v3")
    @Test fun relativeHoldModelAnalysisWithoutLogin() = modelAnalysis("guided-range-v4")
    @Test fun bufferedHoldModelAnalysisWithoutLogin() = modelAnalysis("guided-range-v5")

    @Test fun adaptiveSweepModelAnalysisWithoutLogin() = modelAnalysis("guided-range-v6")

    @Test fun revisedHoldModelAnalysisWithoutLogin() = modelAnalysis("guided-range-v7")

    private fun modelAnalysis(version: String) = runBlocking {
        val buffered = RangeProtocol.buffered(version)
        val adaptive = version in setOf("guided-range-v6", "guided-range-v7")
        val step = if (version == "guided-range-v7") 6 else if (adaptive) 8 else 2
        val relative = version in setOf("guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7")
        assumeTrue(InstrumentationRegistry.getArguments().getString("rangeModelPreview") == "true")
        val rate = 16000
        val partSeconds = if (relative) 2.3 else 4.0
        val partSamples = (rate * partSeconds).toInt()
        val pcmBytes = partSamples * 2 * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
        wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        for (sample in 0 until partSamples * 2) {
            val part = sample / partSamples
            val t = (sample % partSamples).toDouble() / rate
            val hz = 440 * 2.0.pow((60 + part * step - 69 + part * .35) / 12)
            val x = if (t < 1) 0.0 else .25 * sin(2 * PI * hz * t) + .08 * sin(4 * PI * hz * t) + .04 * sin(6 * PI * hz * t)
            wav.putShort((x * 32767).toInt().toShort())
        }
        val submission = RangeSubmission(UUID.randomUUID().toString(), protocolVersion = version, startMidi = 60, noiseRms = .0001,
            durationSeconds = partSeconds * 2, stopReason = "user_stopped", lowerBoundaryStatus = "not_probed", upperBoundaryStatus = "user_stopped",
            sampling = if (adaptive) RangeSampling(strategy="adaptive_$step",coarseStep=step) else null,
            registerEvents = emptyList(), attempts = (0..1).map { part ->
                val offset = part * partSeconds
                RangeAttempt("attempt-$part", 60 + part * step, if (part == 0) "anchor" else "up", offset,
                    offset + .5, offset + .75, offset + partSeconds, offset + if(relative) 1.2 else 1.4, offset + if(relative) 2.2 else 3.4,
                    if (relative || part == 0) "passed" else "not_passed",
                    if(buffered) RangeMetrics(0.0,0.0,1.0,1.0,1.0,continuousHoldSeconds=1.0,
                        actualMidi=60+part*step+part*.35,creditedMidi=60+part*step,stabilityScore=if(version=="guided-range-v7")40.0 else 70.0,
                        timeScore=20.0,steadinessScore=if(version=="guided-range-v7")20.0 else 50.0,creditedHoldSeconds=1.0,bufferSeconds=0.0,outsideSeconds=0.0,
                        excursionPenalty=0.0,referenceMidi=60+part*step+part*.35,
                        referenceRmsCents=if(version=="guided-range-v7")0.0 else null) else null,
                    visitId = "visit-$part", advanceReason = if(buffered) "manual" else "threshold", progressQualified = true,
                    progressEvaluationStart = if(relative) null else offset + 1.4, progressEvaluationEnd = if(relative) null else offset + 3.4, searchOutcome = "reached",
                    referenceStart=if(buffered) offset+1.0 else null,referenceEnd=if(buffered) offset+1.2 else null,
                    referenceMidi=if(buffered) 60+part*step+part*.35 else null,runEndReason=if(buffered) "manual" else null,
                    anchorMidi=if(adaptive)60 else null,probeStage=if(adaptive) if(part==0)"anchor" else "coarse" else null)
            })
        val metadata = Json.moshi.adapter(RangeSubmission::class.java).toJson(submission)
        val report = RangePreviewClient(BuildConfig.RANGE_PREVIEW_BASE_URL).api.analyze(
            MultipartBody.Part.createFormData("audio", "synthetic-test.wav", wav.array().toRequestBody("audio/wav".toMediaType())),
            metadata.toRequestBody("text/plain".toMediaType()))
        assertEquals("model_verified", report.verification)
        assertEquals("passed", report.notes.first { it.midi == 60 }.status)
        assertEquals(if(relative) "passed" else "not_passed", report.notes.first { it.midi == 60+step }.status)
        assertEquals(if(buffered) listOf(listOf(60,60),listOf(60+step,60+step)) else listOf(listOf(60, if(relative) 62 else 60)), report.stableIntervals)
        if (version == "guided-range-v7") {
            val m = report.notes.first { it.midi == 60 }.metrics!!
            assertNotNull(m.referenceRmsCents)
            assertTrue(m.stabilityScore!! in 30.0..40.00001)
        }
        if (adaptive) assertEquals("adaptive_$step",report.sampling!!.strategy)
        if (buffered) {
            assertNull(report.rangeMap)
            assertEquals(1.0,report.notes.first{it.midi==60+step}.metrics!!.creditedHoldSeconds!!,1e-6)
            assertEquals("unmeasured",report.notes.first{it.midi==61}.status)
        } else if (relative) {
            assertEquals(listOf(60,62),report.rangeMap!!.stable)
            assertNull(report.rangeMap.recommended)
            val metrics = report.notes.first { it.midi==60+step }.metrics!!
            assertTrue(metrics.continuousHoldSeconds >= 1.0)
            assertTrue(metrics.continuousHoldSeconds < 1.3)
            assertTrue(metrics.stabilityScore!! in 60.0..80.0)
        }
    }
}
