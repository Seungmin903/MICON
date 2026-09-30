package com.micon.app.range

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoiceQualityAnalyzerTest {
    private data class BoundaryFixture(val frames: List<RangeFrame>, val submission: RangeSubmission)

    private fun boundaryFixture(): BoundaryFixture {
        val session = FreeRangeSession("boundary-reference")
        val frames = mutableListOf<RangeFrame>()
        val step = .02
        var index = 0
        while (session.view().phase != "done" && index < 2_000) {
            val time = index * step
            if (session.view().phase == "transition") session.advance(time)
            val view = session.view()
            val boundary = if (index % 2 == 0) 59.49 else 59.51
            val midi = when (view.phase) {
                "noise" -> null
                "comfort" -> boundary
                "down" -> if (view.elapsed < .6) boundary else 56.5
                "up" -> if (view.elapsed < .6) boundary else 63.5
                else -> error("Unexpected phase: ${view.phase}")
            }
            val frame = RangeFrame(time, step, midi?.let { 440.0 * 2.0.pow((it - 69) / 12) } ?: 0.0,
                if (midi == null) 0.0 else .95, if (midi == null) 0.0 else .22)
            frames += frame
            session.onFrame(frame)
            index++
        }
        assertEquals("done", session.view().phase)
        val submission = requireNotNull(session.submission(frames.last().t + step))
        assertNull(RangeInputQuality.metadataProblem(submission))
        return BoundaryFixture(frames, submission)
    }

    private fun writeBoundaryWav(wav: File, frames: List<RangeFrame>, silent: Boolean = false) {
        val rate = 16_000
        val samples = ((frames.last().t + frames.last().duration) * rate).roundToInt()
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(samples * 2 + 36)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        var phase = 0.0
        repeat(samples) { sample ->
            val frame = frames[(sample / (rate * .02)).toInt().coerceAtMost(frames.lastIndex)]
            phase += 2 * PI * frame.f0 / rate
            val voice = if (silent || frame.f0 == 0.0) 0.0 else
                .22 * sin(phase) + .12 * sin(2 * phase) + .08 * sin(3 * phase)
            bytes.putShort((voice * 32767).toInt().toShort())
        }
        wav.writeBytes(bytes.array())
    }

    @Test fun validComfortHoldCrossingRoundedMidiBoundaryKeepsReferenceScore() {
        val (frames, submission) = boundaryFixture()
        val wav = File.createTempFile("boundary-reference-", ".wav")
        try {
            writeBoundaryWav(wav, frames)
            assertTrue(RangeInputQuality().inspect(submission, wav, 16_000, finalized = true).ready)
            val quality = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission,
                continuousScore = true))
            assertEquals(submission.stages.first().endpointMidi.roundToInt(), quality.referenceMidi)
            assertTrue(quality.referenceMidi in 59..60)
            val reference = quality.points.single { it.midi == quality.referenceMidi }
            assertTrue(reference.holdScore > 0.0)
            assertTrue(reference.score > 0.0)
            // The other rounded bucket is the same sustained comfort note, not a failed pitch attempt.
            val adjacent = if (quality.referenceMidi == 59) 60 else 59
            assertTrue(quality.points.none { it.midi == adjacent })
        } finally { wav.delete() }
    }

    @Test fun missingAcousticReferenceEvidenceDoesNotInventScore() {
        val (frames, submission) = boundaryFixture()
        val wav = File.createTempFile("missing-reference-", ".wav")
        try {
            writeBoundaryWav(wav, frames, silent = true)
            assertNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission, continuousScore = true))

            writeBoundaryWav(wav, frames)
            val comfort = submission.stages.first()
            val referenceFrames = frames.filter { it.t >= comfort.holdStart &&
                it.t + it.duration <= comfort.holdEnd + .05 }
            val keep = referenceFrames.take(2).toSet()
            val sparse = frames.filter { it !in referenceFrames || it in keep }
            assertNull(LocalVoiceQualityAnalyzer.analyze(wav, sparse, submission, continuousScore = true))
        } finally { wav.delete() }
    }

    @Test fun silenceAndNonFiniteAudioNeverBecomePerfectScores() {
        assertNull(LocalVoiceQualityAnalyzer.evidence(DoubleArray(2880), 16000, 220.0))
        assertNull(LocalVoiceQualityAnalyzer.evidence(DoubleArray(2880) { Double.NaN }, 16000, 220.0))
    }

    @Test fun periodicFalsettoIsNotAutomaticallyGoodOrAReferenceHundred() {
        val modal = LocalVoiceQualityAnalyzer.Evidence(8.0, 3.0, .9, 0.0, -6.0)
        val thin = LocalVoiceQualityAnalyzer.Evidence(-8.0, 0.0, .99, 0.0, 16.0)
        assertEquals(1.0, LocalVoiceQualityAnalyzer.registerChange(modal, thin), 1e-9)
        assertEquals(1.0, LocalVoiceQualityAnalyzer.registerChange(thin, thin), 1e-9)
        assertEquals(30.0, LocalVoiceQualityAnalyzer.score(1.0, 0.0, 100.0), 1e-9)
        assertEquals(100.0, LocalVoiceQualityAnalyzer.score(0.0, 0.0, 100.0), 1e-9)
    }

    @Test fun oneSpectralIndicatorContributesSoftlyButCannotForceTheFullThirtyAnchor() {
        val reference = LocalVoiceQualityAnalyzer.Evidence(8.0, 3.0, .9, 0.0, -6.0)
        val fewerHarmonics = reference.copy(richnessDb = -8.0)
        val differentFormant = reference.copy(h2H4Db = 16.0)
        assertTrue(LocalVoiceQualityAnalyzer.registerChange(reference, fewerHarmonics) in 0.0..0.4)
        assertTrue(LocalVoiceQualityAnalyzer.registerChange(reference, differentFormant) in 0.0..0.4)
    }

    @Test fun gradualAcousticChangeProducesDistinctNaturalMiddleScores() {
        val reference = LocalVoiceQualityAnalyzer.Evidence(8.0, 3.0, .95, 0.0, -6.0)
        val scores = (0..10).map { step ->
            val blend = step / 10.0
            val observed = reference.copy(richnessDb = 8.0 - 16 * blend, h2H4Db = -6.0 + 22 * blend)
            LocalVoiceQualityAnalyzer.score(LocalVoiceQualityAnalyzer.registerChange(reference, observed), 0.0, 100.0)
        }
        assertEquals(100.0, scores.first(), 1e-9)
        assertEquals(30.0, scores.last(), 1e-9)
        assertTrue(scores.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(scores.count { it > 30 && it < 100 } >= 6)
        assertTrue(scores.distinct().size >= 8)
    }

    @Test fun lowerOrSamePitchCanStillCarryRegisterEvidenceAndLegacyVersionsRemainReadable() {
        val reference = LocalVoiceQualityAnalyzer.Evidence(8.0, 3.0, .9, 0.0, -6.0)
        val partial = LocalVoiceQualityAnalyzer.Evidence(-2.0, 5.0, .9, 0.0, 0.0)
        val score = LocalVoiceQualityAnalyzer.score(LocalVoiceQualityAnalyzer.registerChange(reference, partial), 0.0, 100.0)
        assertTrue(score in 30.0..95.0)
        assertTrue(LocalVoiceQualityAnalyzer.supports("personal-acoustic-v1"))
        assertTrue(LocalVoiceQualityAnalyzer.supports("personal-acoustic-v2"))
    }

    @Test fun highNotePenalizesHarmonicAndPeriodicityLoss() {
        val reference = LocalVoiceQualityAnalyzer.Evidence(-5.0, 2.0, .96, 0.0)
        val clear = LocalVoiceQualityAnalyzer.Evidence(-6.0, 3.0, .93, 0.0)
        val thin = LocalVoiceQualityAnalyzer.Evidence(-20.0, 18.0, .65, 0.0)
        assertEquals(0.0, LocalVoiceQualityAnalyzer.highChange(reference, clear), 1e-9)
        assertTrue(LocalVoiceQualityAnalyzer.highChange(reference, thin) > .8)
    }

    @Test fun lowNotePenalizesIrregularityButNotThinHarmonicsAlone() {
        val reference = LocalVoiceQualityAnalyzer.Evidence(-5.0, 2.0, .96, 0.0)
        val clear = LocalVoiceQualityAnalyzer.Evidence(-22.0, 18.0, .94, .01)
        val irregular = LocalVoiceQualityAnalyzer.Evidence(-22.0, 18.0, .50, .26)
        assertEquals(0.0, LocalVoiceQualityAnalyzer.lowIrregularity(reference, clear, 0.0), 1e-9)
        assertTrue(LocalVoiceQualityAnalyzer.lowIrregularity(reference, irregular, .8) > .70)
    }

    @Test fun measuredPointsReplaceSyntheticRangeCurve() {
        val rate = 16000
        val wav = File.createTempFile("voice-quality-", ".wav")
        try {
            val samples = rate * 3
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(samples * 2 + 36)
                .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            val frequencies = listOf(220.0, 196.0, 246.94)
            repeat(samples) { sample ->
                val stage = sample / rate
                val angle = 2.0 * PI * frequencies[stage] * sample / rate
                val voice = if (stage == 2) .25 * sin(angle) + .015 * sin(2 * angle)
                    else .25 * sin(angle) + .18 * sin(2 * angle) + .14 * sin(3 * angle) + .10 * sin(4 * angle)
                bytes.putShort((voice * 32767).toInt().toShort())
            }
            wav.writeBytes(bytes.array())
            val midis = listOf(57, 55, 59)
            val frames = (0 until 3).flatMap { stage -> (0 until 30).map { index ->
                RangeFrame(stage + index / 30.0, 1.0 / 30, frequencies[stage], .95, .25)
            } }
            val stages = listOf("comfort", "down", "up").mapIndexed { index, kind ->
                FreeRangeStage(kind, index.toDouble(), index + 1.0, midis[index].toDouble(),
                    midis[index].toDouble(), index + .2, index + .8)
            }
            val submission = RangeSubmission("synthetic", protocolVersion = RangeProtocol.FREE_VERSION,
                startMidi = 57, noiseRms = 0.0, durationSeconds = 3.0, stopReason = "completed",
                lowerBoundaryStatus = "endpoint_held", upperBoundaryStatus = "endpoint_held",
                attempts = emptyList(), registerEvents = emptyList(), stages = stages)
            val legacy = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission))
            assertEquals("personal-acoustic-v1", legacy.version)
            assertTrue(legacy.points.all { it.registerChange == null })
            val quality = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission, continuousScore = true))
            assertEquals("personal-acoustic-v2", quality.version)
            assertTrue(quality.points.all { it.registerChange != null })
            val scores = quality.points.associate { it.midi to it.score }
            assertEquals(57, quality.referenceMidi)
            assertTrue(scores.getValue(55) > 75.0)
            assertTrue(scores.getValue(59) < scores.getValue(57) - 25.0)
        } finally { wav.delete() }
    }

    @Test fun interruptedVoicedPitchIsZeroButNeverSungPitchIsMissing() {
        val rate = 16000
        val wav = File.createTempFile("voice-attempt-", ".wav")
        try {
            val samples = rate * 3
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(samples * 2 + 36)
                .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { sample ->
                val voice = .25 * sin(2 * PI * 220.0 * sample / rate)
                bytes.putShort((voice * 32767).toInt().toShort())
            }
            wav.writeBytes(bytes.array())
            val reference = (0 until 30).map { RangeFrame(it / 30.0, 1.0 / 30, 220.0, .95, .25) }
            val interrupted = (0 until 6).map { RangeFrame(1.1 + it * .13, 1.0 / 30, 196.0, .95, .25) }
            val frames = reference + interrupted
            val stages = listOf("comfort", "down", "up").mapIndexed { index, kind ->
                FreeRangeStage(kind, index.toDouble(), index + 1.0,
                    if (index == 1) 55.0 else 57.0, if (index == 1) 55.0 else 57.0,
                    index + .2, index + .8)
            }
            val submission = RangeSubmission("interrupted", protocolVersion = RangeProtocol.FREE_VERSION,
                startMidi = 57, noiseRms = 0.0, durationSeconds = 3.0, stopReason = "completed",
                lowerBoundaryStatus = "endpoint_held", upperBoundaryStatus = "endpoint_held",
                attempts = emptyList(), registerEvents = emptyList(), stages = stages)
            val quality = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission, continuousScore = true))
            assertEquals(0.0, quality.points.first { it.midi == 55 }.score, 0.0)
            assertNull(quality.points.firstOrNull { it.midi == 56 })
        } finally { wav.delete() }
    }
}
