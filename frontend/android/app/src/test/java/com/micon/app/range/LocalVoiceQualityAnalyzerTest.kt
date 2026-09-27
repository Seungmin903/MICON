package com.micon.app.range

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoiceQualityAnalyzerTest {
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
            val quality = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission))
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
            val quality = requireNotNull(LocalVoiceQualityAnalyzer.analyze(wav, frames, submission))
            assertEquals(0.0, quality.points.first { it.midi == 55 }.score, 0.0)
            assertNull(quality.points.firstOrNull { it.midi == 56 })
        } finally { wav.delete() }
    }
}
