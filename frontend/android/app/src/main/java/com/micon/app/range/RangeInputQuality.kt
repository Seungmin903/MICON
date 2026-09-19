package com.micon.app.range

import com.micon.app.audio.Pcm16MonoWav
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class RangeInputCheck(val ready: Boolean = false, val usableNotes: Int = 0,
    val message: String = "통과한 음의 녹음 구간이 필요해요. 추가로 측정해 주세요.", val canSupplement: Boolean = true)

/** Completed visits only: checkpoint scores cannot qualify an unfinished recording. */
class RangeInputQuality {
    private val checked = mutableMapOf<RangeAttempt, Boolean>()

    @Synchronized fun inspect(submission: RangeSubmission, file: File, rate: Int, finalized: Boolean = false): RangeInputCheck {
        val duration = Pcm16MonoWav.duration(file, rate, finalized)
            ?: return RangeInputCheck(message = if (finalized) "녹음 파일을 확인할 수 없어요. 음역을 다시 측정해 주세요." else "녹음 구간 확인 중…", canSupplement = !finalized)
        if (finalized && submission.attempts.any { it.runEndReason in setOf("checkpoint", "in_progress") }) {
            return RangeInputCheck(message = "완료되지 않은 음의 기록이 있어요. 음역을 다시 측정해 주세요.", canSupplement = false)
        }
        val unfinishedVisits = submission.attempts.filter { it.runEndReason in setOf("checkpoint", "in_progress") }.map { it.visitId }.toSet()
        val attempts = submission.attempts.filter { it.visitId !in unfinishedVisits }
        if (metadataProblem(submission.copy(attempts = attempts)) != null) return RangeInputCheck(message = "측정 정보를 확인할 수 없어요. 음역을 다시 측정해 주세요.", canSupplement = false)
        if (finalized && (abs(duration - submission.durationSeconds) > .15 || attempts.any { it.responseEnd > duration + .001 })) {
            return RangeInputCheck(message = "녹음된 구간이 측정 기록과 맞지 않아요. 음역을 다시 측정해 주세요.", canSupplement = false)
        }
        val successes = attempts.filter { it.selectedForVisit && it.clientStatus == "passed" && it.metrics?.passed == true && it.advanceReason != "anchor_reset" }
        val usable = successes.count { attempt ->
            val end = attempt.evaluationEnd ?: return@count false
            if (end > duration) return@count false // buffered PCM may not have reached disk yet
            checked.getOrPut(attempt) {
                val start = attempt.referenceStart ?: attempt.evaluationStart ?: return@getOrPut false
                val left = ceil(start * rate).toLong()
                val right = floor(end * rate).toLong()
                if (right <= left) return@getOrPut false
                val samples = Pcm16MonoWav.samples(file, left, (right - left).toInt())
                val energy = samples.sumOf { it.toDouble() * it }
                // The existing pass metrics prove pitch/hold. PCM also has to contain audible, unclipped sound.
                val clipped = samples.count { abs(it) >= .99f }.toDouble() / samples.size
                energy / samples.size >= maxOf(.001, submission.noiseRms * 3.16).let { it * it } && clipped <= .01
            }
        }
        val completed = submission.sampling?.let { it.basicCompleted || it.refinementStatus == "completed" }
            ?: (submission.protocolVersion == "guided-range-v1")
        return RangeInputCheck(completed && usable > 0, usable,
            if (completed && usable > 0) "음역 분석 준비 완료" else "통과한 음의 녹음 구간이 필요해요. 추가로 측정해 주세요.")
    }

    companion object {
        fun metadataProblem(s: RangeSubmission): String? {
            if (!RangeProtocol.supports(s.protocolVersion) || !s.noiseRms.isFinite() || s.noiseRms !in 0.0..1.0 ||
                !s.durationSeconds.isFinite() || s.durationSeconds <= 0 || s.startMidi !in 36..84) return "invalid session"
            if (s.mode == "extension" && s.parentAnalysisId.isNullOrBlank() || s.mode == "basic" && s.parentAnalysisId != null) return "invalid parent"
            if (s.protocolVersion in setOf("guided-range-v6", "guided-range-v7")) {
                val sampling = s.sampling ?: return "missing sampling"
                if (sampling.strategy !in (if (s.protocolVersion == "guided-range-v7") setOf("adaptive_6", "adaptive_8") else setOf("adaptive_8")) ||
                    sampling.coarseStep != (if (sampling.strategy == "adaptive_6") 6 else 8)) return "invalid sampling"
            }
            val seen = mutableSetOf<String>()
            var end = 0.0
            for (a in s.attempts) {
                if (!seen.add(a.attemptId) || a.targetMidi !in 36..84 ||
                    listOf(a.cueStart, a.cueEnd, a.responseStart, a.responseEnd).any { !it.isFinite() || it < 0 } ||
                    a.cueStart < end - .001 || a.cueStart > a.cueEnd || a.cueEnd > a.responseStart ||
                    a.responseStart > a.responseEnd || a.responseEnd > s.durationSeconds + .05) return "invalid timeline"
                val empty = a.clientStatus == "unmeasured" && a.evaluationStart == null && a.cueEnd == a.responseStart && a.responseStart == a.responseEnd
                if (!empty && (a.responseStart >= a.responseEnd || a.responseStart < a.cueEnd + .25 - .001)) return "missing cue guard"
                end = a.responseEnd
                if ((a.evaluationStart == null) != (a.evaluationEnd == null)) return "missing evaluation"
                if (a.evaluationStart != null) {
                    val start = a.evaluationStart; val stop = a.evaluationEnd!!
                    if (!start.isFinite() || !stop.isFinite() || start < a.responseStart + .17 - .001 ||
                        start >= stop || stop > a.responseEnd + .001 || stop - start > 2.501) return "invalid evaluation"
                    val m = a.metrics ?: return "missing metrics"
                    if (listOfNotNull(m.medianAbsErrorCents, m.pitchSpreadCents, m.actualMidi, m.stabilityScore,
                            m.timeScore, m.steadinessScore, m.referenceMidi, m.referenceRmsCents,
                            m.creditedHoldSeconds, m.bufferSeconds, m.outsideSeconds, m.excursionPenalty,
                            m.inTuneRatio, m.voicedRatio, m.longestHoldSeconds, m.continuousHoldSeconds, m.clippedRatio)
                            .any { !it.isFinite() }) return "invalid metrics"
                    if (RangeProtocol.buffered(s.protocolVersion)) {
                        val refStart = a.referenceStart ?: return "missing reference"
                        val refEnd = a.referenceEnd ?: return "missing reference"
                        val refMidi = a.referenceMidi ?: return "missing reference"
                        val hold = m.creditedHoldSeconds ?: return "missing hold"
                        val buffer = m.bufferSeconds ?: return "missing buffer"
                        val outside = m.outsideSeconds ?: return "missing outside"
                        val penalty = m.excursionPenalty ?: return "missing penalty"
                        if (a.runEndReason.isNullOrBlank() || listOf(refStart, refEnd, refMidi, hold, buffer, outside, penalty).any { !it.isFinite() } ||
                            refStart < a.responseStart - .001 || abs(refEnd - refStart - .2) > .001 ||
                            abs(refEnd - start) > .001 || abs(refMidi - a.targetMidi) > RangeProtocol.START_TOLERANCE_SEMITONES + .000001 ||
                            hold !in 0.0..2.500001 || buffer !in 0.0..2.500001 || outside !in 0.0..2.500001 ||
                            m.continuousHoldSeconds > hold + .001 || abs(hold + buffer + outside - (stop - start)) > .001 ||
                            abs(penalty - minOf(20.0, 40 * (buffer + 2 * outside))) > .001 ||
                            m.creditedMidi?.let { it !in 36..84 } == true ||
                            (hold >= 1 - 1e-8 && m.creditedMidi != null) != (a.clientStatus == "passed") ||
                            s.protocolVersion == "guided-range-v7" && (m.referenceRmsCents == null || m.referenceRmsCents < 0)) return "invalid reference evidence"
                    }
                } else if (a.clientStatus in setOf("passed", "not_passed")) return "missing scored window"
                if (s.protocolVersion != "guided-range-v1" && (a.visitId.isNullOrBlank() || a.advanceReason.isNullOrBlank())) return "missing visit"
                if (s.protocolVersion in setOf("guided-range-v6", "guided-range-v7") &&
                    (a.anchorMidi !in 36..84 || a.probeStage !in when (a.direction) {
                        "anchor" -> setOf("anchor"); "up", "down" -> setOf("coarse", "bisect")
                        "extension", "reconfirm" -> setOf("refinement"); else -> emptySet()
                    })) return "missing sampling"
            }
            if (s.protocolVersion != "guided-range-v1" && s.attempts.groupBy { it.visitId }.values.any { group ->
                    group.count { it.selectedForVisit } != 1 || group.map { it.advanceReason }.distinct().size != 1
                }) return "invalid visit"
            return null
        }
    }
}
