package com.micon.app.range

import com.micon.app.audio.Pcm16MonoWav
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** On-device acoustic comparison against the user's first held note. No model or network call. */
object LocalVoiceQualityAnalyzer {
    const val VERSION = "personal-acoustic-v1"
    internal data class Evidence(val richnessDb: Double, val h1H2Db: Double,
        val periodicity: Double, val doublePeriodAdvantage: Double)

    fun analyze(wav: File, frames: List<RangeFrame>, submission: RangeSubmission): VoiceQualityResult? {
        require(submission.protocolVersion == RangeProtocol.FREE_VERSION)
        val comfort = submission.stages.firstOrNull { it.kind == "comfort" } ?: return null
        val held = heldNotes(frames, submission)
        val referenceMidi = comfort.endpointMidi.roundToInt()
        if (referenceMidi !in held) return null
        val rate = Pcm16MonoWav.sampleRate(wav)
        require(Pcm16MonoWav.duration(wav, rate, true) != null) { "WAV 녹음을 확인할 수 없어요." }
        val floor = max(.001, submission.noiseRms * 3.16)
        RandomAccessFile(wav, "r").use { audio ->
            val referenceFrames = frames.filter { it.t >= comfort.holdStart &&
                it.t + it.duration <= comfort.holdEnd + .05 && usable(it, floor) }
            val reference = medianEvidence(audio, rate, referenceFrames) ?: return null
            val candidatesByMidi = frames.filter { frame -> usable(frame, floor) &&
                submission.stages.any { frame.t >= it.start && frame.t < it.end } }
                .groupBy { RangeProtocol.midi(it.f0).roundToInt() }
            // A short, interrupted voiced attempt is a measured 0, not an unmeasured pitch.
            // Require several tracked frames so one stray pitch estimate cannot create a score.
            val attempted = candidatesByMidi.filterValues { noteFrames ->
                val voiced = noteFrames.filter { RangeEvaluator.voiced(it, floor) }
                voiced.size >= 3 && voiced.sumOf { it.duration.coerceAtMost(.04) } >= .1
            }
            val points = (held.keys + attempted.keys).sorted().mapNotNull { midi ->
                val hold = held[midi] ?: return@mapNotNull VoiceQualityPoint(midi, 0.0, 0.0, 0.0, 0.0)
                val candidates = candidatesByMidi[midi].orEmpty()
                val current = medianEvidence(audio, rate, candidates) ?: return@mapNotNull null
                val upper = if (midi > referenceMidi) highChange(reference, current) else 0.0
                val lower = if (midi < referenceMidi) lowIrregularity(reference, current,
                    pitchWobble(candidates), untrackedActiveRatio(frames, submission, midi, floor)) else 0.0
                val score = ((100.0 - 75.0 * upper - 75.0 * lower).coerceIn(0.0, 100.0) *
                    (.65 + .35 * hold / 100.0)).coerceIn(0.0, 100.0)
                VoiceQualityPoint(midi, score, upper, lower, hold)
            }
            return VoiceQualityResult(VERSION, referenceMidi, points)
                .takeIf { result -> result.points.any { it.midi == referenceMidi } }
        }
    }

    /** One valid pitch run earns one note; missing notes never receive invented scores. */
    private fun heldNotes(frames: List<RangeFrame>, submission: RangeSubmission): Map<Int, Double> {
        val floor = max(.001, submission.noiseRms * 3.16)
        val best = mutableMapOf<Int, Pair<Double, Double>>()
        submission.stages.forEach { stage ->
            val eligible = frames.filter { it.t >= stage.start && it.t < stage.end &&
                it.clippedRatio <= .01 && RangeEvaluator.voiced(it, floor) }
                .sortedBy { it.t }
            var run = mutableListOf<RangeFrame>()
            var note: Int? = null
            fun finish() {
                val current = note ?: return
                val first = run.firstOrNull() ?: return
                val observed = run.sumOf { it.duration.coerceAtMost(.04) }
                val span = run.last().t + run.last().duration.coerceAtMost(.04) - first.t
                if (observed >= .3 && span > 0) {
                    val score = (100.0 * observed / max(span, .6)).coerceIn(0.0, 100.0)
                    if ((best[current]?.first ?: 0.0) < observed) best[current] = observed to score
                }
                run = mutableListOf()
            }
            eligible.forEach { frame ->
                val current = RangeProtocol.midi(frame.f0).roundToInt()
                val previous = run.lastOrNull()
                if (previous != null && (current != note || frame.t - previous.t - previous.duration > .1)) finish()
                note = current
                run += frame
            }
            finish()
        }
        return best.mapValues { it.value.second }
    }

    private fun usable(frame: RangeFrame, floor: Double) = frame.t.isFinite() &&
        frame.duration.isFinite() && frame.duration > 0 && frame.f0.isFinite() && frame.f0 > 0 &&
        frame.rms >= floor && frame.clippedRatio <= .01 &&
        RangeProtocol.midi(frame.f0) in RangeProtocol.MIN_MIDI.toDouble()..RangeProtocol.MAX_MIDI.toDouble()

    private fun medianEvidence(audio: RandomAccessFile, rate: Int, frames: List<RangeFrame>): Evidence? {
        if (frames.size < 3) return null
        val stride = max(1, frames.size / 30)
        val count = (rate * .18).roundToInt()
        val rows = frames.filterIndexed { index, _ -> index % stride == 0 }.mapNotNull { frame ->
            val center = ((frame.t + frame.duration / 2.0) * rate).toLong()
            if (center < count / 2 || center + count / 2 >= (audio.length() - 44) / 2) null
            else {
                val bytes = ByteArray(count * 2)
                audio.seek(44 + (center - count / 2) * 2)
                audio.readFully(bytes)
                val samples = DoubleArray(count) { index ->
                    val low = bytes[index * 2].toInt() and 255
                    val high = bytes[index * 2 + 1].toInt()
                    ((high shl 8) or low).toShort() / 32768.0
                }
                evidence(samples, rate, frame.f0)
            }
        }
        if (rows.size < 3) return null
        return Evidence(median(rows.map { it.richnessDb }), median(rows.map { it.h1H2Db }),
            median(rows.map { it.periodicity }), median(rows.map { it.doublePeriodAdvantage }))
    }

    /** These are uncorrected acoustic proxies, not measured glottal closed quotient. */
    internal fun evidence(input: DoubleArray, rate: Int, f0: Double): Evidence? {
        if (input.size < 256 || f0 !in 65.0..1200.0) return null
        val mean = input.average()
        val samples = DoubleArray(input.size) { input[it] - mean }
        val period = (rate / f0).roundToInt()
        if (period < 2 || 2 * period >= samples.size / 2) return null
        val one = correlation(samples, period)
        val two = correlation(samples, 2 * period)
        val windowed = DoubleArray(samples.size) { index ->
            samples[index] * (.5 - .5 * cos(2.0 * PI * index / (samples.size - 1))) }
        val amplitudes = (1..6).map { harmonic -> amplitude(windowed, rate, f0 * harmonic) }
        val low = sqrt(amplitudes[0] * amplitudes[0] + amplitudes[1] * amplitudes[1])
        val high = sqrt(amplitudes.drop(2).sumOf { it * it })
        val richness = 20.0 * ln((high + 1e-8) / (low + 1e-8)) / ln(10.0)
        val h1h2 = 20.0 * ln((amplitudes[0] + 1e-8) / (amplitudes[1] + 1e-8)) / ln(10.0)
        return Evidence(richness, h1h2, one.coerceIn(0.0, 1.0),
            (two - one).coerceIn(0.0, 1.0))
    }

    private fun amplitude(windowed: DoubleArray, rate: Int, frequency: Double): Double {
        if (frequency >= rate / 2.0) return 0.0
        var best = 0.0
        for (offset in -4..4 step 4) {
            val step = 2.0 * PI * (frequency + offset) / rate
            val stepRe = cos(step)
            val stepIm = sin(step)
            var re = 1.0; var im = 0.0; var sumRe = 0.0; var sumIm = 0.0
            for (sample in windowed) {
                sumRe += sample * re; sumIm += sample * im
                val nextRe = re * stepRe - im * stepIm
                im = re * stepIm + im * stepRe; re = nextRe
            }
            best = max(best, sqrt(sumRe * sumRe + sumIm * sumIm))
        }
        return best
    }

    private fun correlation(samples: DoubleArray, lag: Int): Double {
        var cross = 0.0; var left = 0.0; var right = 0.0
        for (index in lag until samples.size) {
            val a = samples[index]; val b = samples[index - lag]
            cross += a * b; left += a * a; right += b * b
        }
        return cross / sqrt(left * right + 1e-12)
    }

    internal fun highChange(reference: Evidence, observed: Evidence): Double {
        val richness = ((reference.richnessDb - observed.richnessDb - 2.0) / 12.0).coerceIn(0.0, 1.0)
        val dominance = ((observed.h1H2Db - reference.h1H2Db - 2.0) / 12.0).coerceIn(0.0, 1.0)
        val periodicity = ((reference.periodicity - observed.periodicity - .05) / .35).coerceIn(0.0, 1.0)
        return .5 * richness + .3 * dominance + .2 * periodicity
    }

    internal fun lowIrregularity(reference: Evidence, observed: Evidence, wobble: Double,
        untracked: Double = 0.0): Double {
        val periodicity = ((reference.periodicity - observed.periodicity - .04) / .35).coerceIn(0.0, 1.0)
        val doubled = ((observed.doublePeriodAdvantage - reference.doublePeriodAdvantage - .03) / .3).coerceIn(0.0, 1.0)
        return .45 * periodicity + .20 * doubled + .15 * wobble + .20 * untracked.coerceIn(0.0, 1.0)
    }

    private fun pitchWobble(frames: List<RangeFrame>): Double {
        val steps = frames.sortedBy { it.t }.zipWithNext().mapNotNull { (a, b) ->
            if (b.t - a.t > .10) null else abs(RangeProtocol.midi(b.f0) - RangeProtocol.midi(a.f0)) }
        return if (steps.isEmpty()) 0.0 else ((median(steps) - .12) / .55).coerceIn(0.0, 1.0)
    }

    private fun untrackedActiveRatio(frames: List<RangeFrame>, submission: RangeSubmission,
        midi: Int, floor: Double): Double {
        val down = submission.stages.firstOrNull { it.kind == "down" } ?: return 0.0
        var lastNote: Int? = null; var lastTime = Double.NEGATIVE_INFINITY
        var tracked = 0.0; var lost = 0.0
        frames.filter { it.t >= down.start && it.t < down.end }.sortedBy { it.t }.forEach { frame ->
            if (RangeEvaluator.voiced(frame, floor) && frame.f0 > 0.0) {
                lastNote = RangeProtocol.midi(frame.f0).roundToInt(); lastTime = frame.t
                if (lastNote == midi) tracked += frame.duration.coerceAtMost(.04)
            } else if (lastNote == midi && frame.t - lastTime <= .8 &&
                frame.rmsLow >= floor * 1.5 && frame.clippedRatio <= .01) {
                lost += frame.duration.coerceAtMost(.04)
            }
        }
        return if (tracked + lost < .3) 0.0 else lost / (tracked + lost)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        else sorted[sorted.size / 2]
    }
}
