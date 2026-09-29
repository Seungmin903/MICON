package com.micon.app.range

import com.micon.app.audio.Pcm16MonoWav
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in private recording test. Raw audio/labels never enter app inference or the Git repository. */
class VoiceCalibrationRecordingTest {
    private data class Recording(val name: String, val label: String, val frames: List<RangeFrame>,
        val evidence: LocalVoiceQualityAnalyzer.Evidence, val midi: Double, val untracked: Double)

    private fun median(values: List<Double>) = RangeEvaluator.median(values)!!

    private fun recording(root: File, name: String, label: String, gain: Double): Recording {
        val wav = File(root, "$name.wav")
        val rate = Pcm16MonoWav.sampleRate(wav)
        val frames = File(root, "$name.csv").readLines().drop(1).map { line ->
            val f = line.split(',').map(String::toDouble)
            RangeFrame(f[0], .02, f[1], f[2], f[3] * gain, f[4] * gain)
        }
        val active = frames.filter { it.rmsLow >= .003 * gain }
        val voiced = active.filter { RangeEvaluator.voiced(it, .003 * gain) }
        val selected = voiced.filterIndexed { index, _ -> index % maxOf(1, voiced.size / 40) == 0 }
        val width = (rate * .18).roundToInt()
        val rows = selected.mapNotNull { frame ->
            val center = (frame.t * rate).toLong()
            if (center < width / 2 || center + width / 2 >= (wav.length() - 44) / 2) null else {
                val pcm = Pcm16MonoWav.samples(wav, center - width / 2, width)
                LocalVoiceQualityAnalyzer.evidence(DoubleArray(pcm.size) { pcm[it].toDouble() * gain }, rate, frame.f0)
            }
        }
        require(rows.size >= 3)
        val evidence = LocalVoiceQualityAnalyzer.Evidence(median(rows.map { it.richnessDb }),
            median(rows.map { it.h1H2Db }), median(rows.map { it.periodicity }),
            median(rows.map { it.doublePeriodAdvantage }), median(rows.map { it.h2H4Db }))
        return Recording(name, label, voiced, evidence, median(voiced.map { RangeProtocol.midi(it.f0) }),
            1 - voiced.size.toDouble() / active.size)
    }

    @Test fun evaluatesPrivateRecordingsAndTwelveDbGainInvariance() {
        val directory = System.getenv("MICON_VOICE_CALIBRATION_DIR")
        assumeTrue("Private recordings are opt-in; no embedded audio in CI", !directory.isNullOrBlank())
        val root = File(directory!!)
        val manifest = File(root, "manifest.tsv").readLines().map { it.split('\t') }
        val recordings = manifest.map { recording(root, it[0], it[1], 1.0) }
        // Explicit experimental reference. Filenames/labels are not used by the production analyzer.
        val reference = recordings.first { it.name == "진성" }
        val output = mutableListOf("name\tlabel\tv1_component\tv2_component\tminus12db\tregisterChange\tlowIrregularity")
        val referenceSensitivity = mutableListOf("name\treference\tregisterComponentScore")
        recordings.forEach { observed ->
            fun components(current: Recording): Triple<Double, Double, Double> {
                val register = LocalVoiceQualityAnalyzer.registerChange(reference.evidence, current.evidence)
                val wobble = current.frames.zipWithNext().mapNotNull { (a, b) ->
                    if (b.t - a.t > .1) null else abs(RangeProtocol.midi(b.f0) - RangeProtocol.midi(a.f0)) }
                val wobbleScore = if (wobble.isEmpty()) 0.0 else ((median(wobble) - .12) / .55).coerceIn(0.0, 1.0)
                val low = if (current.midi < reference.midi) LocalVoiceQualityAnalyzer.lowIrregularity(
                    reference.evidence, current.evidence, wobbleScore, current.untracked) else 0.0
                // Component comparison assumes a fully held note; not a complete onboarding-session result.
                return Triple(LocalVoiceQualityAnalyzer.score(register, low, 100.0), register, low)
            }
            val (score, register, low) = components(observed)
            val quieter = recording(root, observed.name, observed.label, .2511886432)
            val quieterScore = components(quieter).first
            assertEquals("Gain alone changed ${observed.name}", score, quieterScore, .15)
            when (observed.label) {
                "modal" -> assertTrue("${observed.name}: $score", score >= 90)
                "falsetto" -> assertTrue("${observed.name}: $score", score in 25.0..45.0)
                "mixed" -> assertTrue("${observed.name}: $score", score > 30 && score < 95)
                "broken-low" -> assertTrue("${observed.name}: $score", score < 80)
            }
            val oldHigh = if (observed.midi > reference.midi)
                LocalVoiceQualityAnalyzer.highChange(reference.evidence, observed.evidence) else 0.0
            val oldScore = (100 - 75 * oldHigh - 75 * low).coerceIn(0.0, 100.0)
            output += "${observed.name}\t${observed.label}\t$oldScore\t$score\t$quieterScore\t$register\t$low"
            recordings.filter { it.label == "modal" }.forEach { alternateReference ->
                val change = LocalVoiceQualityAnalyzer.registerChange(alternateReference.evidence, observed.evidence)
                // Isolate the acoustic continuum from low-note/hold effects for reference sensitivity.
                val component = LocalVoiceQualityAnalyzer.score(change, 0.0, 100.0)
                referenceSensitivity += "${observed.name}\t${alternateReference.name}\t$component"
            }
        }
        File(root, "android-score-comparison.tsv").writeText(output.joinToString("\n") + "\n")
        File(root, "reference-sensitivity.tsv").writeText(referenceSensitivity.joinToString("\n") + "\n")
    }
}
