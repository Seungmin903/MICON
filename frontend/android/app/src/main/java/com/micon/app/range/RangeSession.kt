package com.micon.app.range

import kotlin.math.max
import kotlin.math.roundToInt

/** Audio timestamps, never wall time, drive evaluation and session progress. */
class RangeSession(val id: String, private val parent: RangeReport? = null,
                   private val protocolVersion: String = RangeProtocol.VERSION,
                   private val awaitAnchorConfirmation: Boolean = false,
                   private val previousCompletedTargets: Set<Int>? = null,
                   private val awaitComfortableConfirmation: Boolean = false) {
    private val revisedSearch = protocolVersion == "guided-range-v7"
    private val adaptive = protocolVersion in setOf("guided-range-v6", "guided-range-v7")
    private val coarseStep = if (protocolVersion == "guided-range-v7") 6 else 8
    private val buffered = RangeProtocol.buffered(protocolVersion)
    private val relative = buffered || protocolVersion == "guided-range-v4"
    private var tracker: RelativeHoldTracker? = null
    private var bufferedTracker: BufferedHoldTracker? = null
    private var bestEnd: Double? = null
    var diagnosticsEnabled = false
    var lastDiagnostic: FrameDiagnostic? = null; private set
    data class FrameDiagnostic(
        val phase: String, val targetMidi: Int?, val visitId: String?, val cueId: Int,
        val responseStart: Double?, val onset: Double?, val rmsFloor: Double,
        val referenceMidi: Double?, val runStartBefore: Double?, val runEndBefore: Double?,
        val holdBefore: Double, val decision: String,
        val runStartAfter: Double? = null, val holdAfter: Double = 0.0,
        val bestStart: Double? = null, val bestEnd: Double? = null, val advance: String? = null,
    )
    data class View(
        val phase: String, val targetMidi: Int?, val currentMidi: Double?, val cents: Double?,
        val elapsed: Double, val holdProgress: Float, val metrics: RangeMetrics?,
        val cueId: Int, val cueMidi: Int?, val waitingForCue: Boolean,
        val message: String, val attempts: List<RangeAttempt>, val done: Boolean,
        val cueDurationSeconds: Double, val bestMetrics: RangeMetrics?, val visitId: String?,
        val inputSuppressed: Boolean, val sampling: RangeSampling,
        val referenceMidi: Double? = null, val pitchZone: String? = null,
        val referenceGeneration: Int = 0, val rmsFloor: Double = .001,
        val probeStage: String? = null, val canResetAnchor: Boolean = false,
    )
    private var phase = "noise"
    private var now = 0.0
    private var comfortableStart = 0.0
    private var anchor: Int? = null
    private var target: Int? = null
    private var search: RangeSearch? = null
    private var cueId = 0
    private var cueMidi: Int? = null
    private var cueDurationSeconds = .5
    private var waitingForCue = false
    private var cueStart = 0.0
    private var cueEnd: Double? = null
    private var responseStart: Double? = null
    private var onset: Double? = null
    private var bestStart: Double? = null
    private var best: RangeMetrics? = null
    private var latest: RangeMetrics? = null
    private var progressStart: Double? = null
    private var displayHold = 0.0
    private var currentMidi: Double? = null
    private var noise = 0.0
    private var floor = .001
    private var retryOf: String? = null
    private var visitSerial = 0
    private var visitId: String? = null
    private var lastManualTime = Double.NEGATIVE_INFINITY
    private var register = "unknown"
    private var stopReason = "completed"
    private var lower = "not_probed"
    private var upper = "not_probed"
    private var message = "잠시 조용히 계세요. 주변 소리를 확인해요"
    private var sampling = parent?.sampling ?: if (adaptive) RangeSampling(strategy = "adaptive_$coarseStep", coarseStep = coarseStep) else RangeSampling()
    private val calibration = mutableListOf<RangeFrame>()
    private val frames = mutableListOf<RangeFrame>()
    private val attempts = mutableListOf<RangeAttempt>()
    private val registerEvents = mutableListOf<RegisterEvent>()
    private val central = ArrayDeque<Int>()
    private val centralResults = mutableMapOf<Int, RangeAttempt>()
    private val refinement = ArrayDeque<Int>()

    init {
        require(parent == null || parent.protocolVersion == protocolVersion)
        if (parent != null) {
            val measured = parent.notes.filter { it.metrics != null || it.successfulAttempts > 0 }.map { it.midi }.sorted()
            require(measured.isNotEmpty())
            anchor = measured[measured.size / 2]
            lower = parent.lowerBoundaryStatus; upper = parent.upperBoundaryStatus
            if (parent.sampling == null) sampling = RangeSampling(upperEnd = measured.last(), lowerEnd = measured.first(),
                upperLastSuccess = parent.highMidi, lowerLastSuccess = parent.lowMidi)
        }
    }
    @Synchronized fun onFrame(f: RangeFrame): View {
        lastDiagnostic = if (diagnosticsEnabled) FrameDiagnostic(phase, target, visitId, cueId,
            responseStart, onset, floor, tracker?.referenceMidi, tracker?.current?.start,
            tracker?.current?.end, tracker?.current?.metrics?.continuousHoldSeconds ?: 0.0,
            when {
                phase in listOf("done", "profile_ready", "anchor_ready", "comfortable_ready") -> "paused"
                !f.t.isFinite() || f.t + f.duration < now - 1e-6 -> "stale_frame"
                phase in listOf("noise", "comfortable") -> phase
                waitingForCue -> "reference_playing"
                responseStart == null || f.t < responseStart!! -> "reference_decay"
                else -> "onset_settling"
            }) else null
        if (phase in listOf("done", "profile_ready", "anchor_ready", "comfortable_ready") || !f.t.isFinite() || f.t + f.duration < now - 1e-6) return view()
        now = max(now, f.t + f.duration)
        currentMidi = if (RangeEvaluator.voiced(f, floor)) RangeProtocol.midi(f.f0) else null
        if (phase == "noise") {
            calibration.add(f)
            if (buffered) calibration.removeAll { it.t + it.duration <= now - 1.0 + 1e-8 }
            val quietWindow = !buffered || calibration.sumOf {
                if (RangeEvaluator.voiced(it, .001)) it.duration else 0.0
            } < .1 - 1e-8
            if (now >= 1.0 && quietWindow && (!buffered || calibration.sumOf { it.duration } >= 1.0 - 1e-8)) {
                noise = RangeEvaluator.median(calibration.map { it.rms }) ?: 0.0
                floor = max(.001, noise * 3.16); calibration.clear()
                if (parent == null) {
                    phase = if (awaitComfortableConfirmation) "comfortable_ready" else "comfortable"
                    comfortableStart = now
                    if (awaitComfortableConfirmation) currentMidi = null
                    message = "편한 목소리로 ‘아’를 2초 내주세요"
                }
                else startRefinement(now)
            } else if (buffered && now >= 3.0) message = "목소리가 섞이지 않은 조용한 1초가 필요해요. 잠시 기다려 주세요"
            return view()
        }
        if (phase == "comfortable") {
            if (adaptive && f.t < comfortableStart) return view()
            calibration.add(f); calibration.removeAll { it.t < now - 2.0 }
            val valid = calibration.filter { RangeEvaluator.voiced(it, floor) && it.clippedRatio <= .01 }
            if (now >= (if (adaptive) comfortableStart + 2.0 else 3.0) && valid.sumOf { it.duration } >= 1.0) {
                val m = RangeEvaluator.median(valid.map { RangeProtocol.midi(it.f0) })!!.roundToInt()
                if (m !in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI) message = "C2~C6 안에서 조금 더 편한 음으로 불러주세요"
                else {
                    anchor = m; phase = "anchor"
                    if (!adaptive) central.addAll(listOf(m + 4, m - 4).filter { it in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI })
                    message = if (adaptive) "편한 시작음 하나를 확인해요" else "시작음과 위·아래 음을 확인해요"
                    calibration.clear()
                    if (awaitAnchorConfirmation) {
                        phase = "anchor_ready"; target = null; cueMidi = null; waitingForCue = false
                        currentMidi = null
                    } else startAttempt(m, now)
                }
            }
            return view()
        }
        val response = responseStart
        if (!waitingForCue && response != null && f.t >= response && target != null) {
            if (buffered) {
                val active = bufferedTracker!!
                active.accept(f)
                latest = active.current?.metrics
                displayHold = latest?.recordedHoldSeconds ?: 0.0
                active.best?.let { best = it.metrics; bestStart = it.start; bestEnd = it.end }
                val advance = active.ended?.let { it.metrics.passed && it.endReason in
                    setOf("voice_ended", "buffer_timeout", "outside_timeout", "max_duration") } == true
                lastDiagnostic = lastDiagnostic?.copy(referenceMidi = active.referenceMidi,
                    decision = active.lastDecision, runStartAfter = active.current?.start,
                    holdAfter = displayHold, bestStart = bestStart, bestEnd = bestEnd,
                    advance = if (advance) active.ended?.endReason else null)
                if (advance) completeAttempt("threshold", now)
                return view()
            }
            frames.add(f); frames.removeAll { it.t + it.duration < now - RangeProtocol.WINDOW }
            if (onset == null && RangeEvaluator.voiced(f, floor)) onset = f.t
            val settled = onset?.plus(.2)
            if (settled != null && now > settled) {
                if (relative) {
                    val left = max(f.t, settled)
                    val previousRun = tracker!!.current
                    if (now > left) tracker!!.accept(f.copy(t = left, duration = now - left))
                    latest = tracker!!.current?.metrics
                    displayHold = latest?.continuousHoldSeconds ?: 0.0
                    tracker!!.best?.let { best = it.metrics; bestStart = it.start; bestEnd = it.end }
                    val passedRunEnded = previousRun != null && previousRun.metrics.passed &&
                        tracker!!.current?.start != previousRun.start
                    val fullHold = best?.let { it.passed && it.continuousHoldSeconds >= RangeProtocol.FULL_HOLD_SECONDS - 1e-8 } == true
                    lastDiagnostic = lastDiagnostic?.copy(onset = onset, decision = tracker!!.lastDecision,
                        runStartAfter = tracker!!.current?.start, holdAfter = displayHold,
                        bestStart = bestStart, bestEnd = bestEnd,
                        advance = when { fullHold -> "full_2_5_seconds"; passedRunEnded -> "passed_run_ended"; else -> null })
                    if (passedRunEnded || fullHold) {
                        completeAttempt("threshold", now)
                    }
                    return view()
                }
                val start = max(settled, now - RangeProtocol.WINDOW)
                val score = RangeEvaluator.evaluate(frames, target!!, start, now, floor)
                displayHold = score.continuousHoldSeconds
                if (now - settled >= RangeProtocol.WINDOW - 1e-8) {
                    latest = score
                    if (eligible(score) && (best == null || score.inTuneRatio > best!!.inTuneRatio + 1e-8)) {
                        best = score; bestStart = start
                    }
                    if (eligible(score) && score.continuousHoldSeconds >= RangeProtocol.ADVANCE_HOLD_SECONDS - 1e-8) {
                        progressStart = start; completeAttempt("threshold", now); return view()
                    }
                }
            }
        }
        return view()
    }
    private fun eligible(m: RangeMetrics) = m.voicedRatio >= .5 - 1e-8 && m.clippedRatio <= .01 + 1e-8

    /** Noise calibration is frozen until the onboarding intro has finished fading out. */
    @Synchronized fun startComfortableMeasurement(audioTime: Double): Boolean {
        if (phase != "comfortable_ready") return false
        now = max(now, audioTime); comfortableStart = now; phase = "comfortable"
        return true
    }

    /** The onboarding guide holds both the first cue and the audio evaluation clock. */
    @Synchronized fun startAnchorMeasurement(audioTime: Double): Boolean {
        if (phase != "anchor_ready") return false
        now = max(now, audioTime); phase = "anchor"
        startAttempt(anchor!!, now)
        return true
    }

    @Synchronized fun cueFinished(serial: Int, audioTime: Double) {
        if (serial != cueId || !waitingForCue || phase in listOf("done", "profile_ready")) return
        now = max(now, audioTime); waitingForCue = false
        cueEnd = max(cueStart, audioTime); responseStart = cueEnd!! + .25
    }
    @Synchronized fun replay(audioTime: Double) {
        if (phase in listOf("done", "profile_ready") || target == null || waitingForCue) return
        now = max(now, audioTime)
        val midi = target!!
        addAttempt("replay", now)
        startAttempt(midi, now, attempts.lastOrNull()?.attemptId, cueSeconds = 1.0, sameVisit = true)
    }
    @Synchronized fun next(expectedVisit: String?, audioTime: Double): Boolean {
        if (phase in listOf("done", "profile_ready") || target == null || expectedVisit != visitId || audioTime - lastManualTime < .5) return false
        if (adaptive && phase == "anchor") return resetAnchor(expectedVisit, audioTime)
        now = max(now, audioTime); lastManualTime = now
        completeAttempt("manual", now); return true
    }
    @Synchronized fun resetAnchor(expectedVisit: String?, audioTime: Double): Boolean {
        if (!adaptive || phase != "anchor" || target == null || expectedVisit != visitId || audioTime - lastManualTime < .5) return false
        now = max(now, audioTime); lastManualTime = now
        closeVisit("anchor_reset", now)
        anchor = null; phase = "comfortable"; cueMidi = null; cueId++
        comfortableStart = now + .25
        responseStart = null; cueEnd = null; onset = null; visitId = null
        tracker = null; bufferedTracker = null; best = null; latest = null
        bestStart = null; bestEnd = null; displayHold = 0.0; currentMidi = null
        frames.clear(); calibration.clear()
        message = "편한 시작음을 다시 찾아요. ‘아’를 2초 내주세요"
        return true
    }
    @Synchronized fun continueMeasurement(audioTime: Double): Boolean {
        if (phase != "profile_ready") return false
        now = max(now, audioTime); startRefinement(now); return true
    }
    @Synchronized fun waitForValidInput() {
        phase = "profile_ready"; target = null; cueMidi = null; currentMidi = null
        message = "분석할 녹음 구간이 부족해요. 추가로 측정해 주세요."
    }
    @Synchronized fun canSupplement(): Boolean {
        val start = anchor ?: return false
        val completed = (previousCompletedTargets ?: parent?.completedNoteFallback().orEmpty()) + submission().completedTargets()
        return RangeRefinement.targets(start, sampling, completed, reconfirmBoundaries = !revisedSearch).isNotEmpty()
    }
    @Synchronized fun markFalsetto() {
        if (phase in listOf("done", "profile_ready") || register == "falsetto") return
        register = "falsetto"; registerEvents.add(RegisterEvent(now, register))
    }
    @Synchronized fun stop(reason: String = "user_stopped", audioTime: Double = now): RangeSubmission {
        if (phase != "done") finish(reason, max(now, audioTime))
        return submission()
    }
    private fun startAttempt(midi: Int, t: Double, retry: String? = null,
                             cueSeconds: Double = if (phase in listOf("anchor", "central")) 1.0 else .5, sameVisit: Boolean = false) {
        if (!sameVisit) { visitSerial++; visitId = id + "-visit-" + visitSerial }
        target = midi; cueMidi = midi; cueStart = t; cueEnd = null; responseStart = null
        waitingForCue = true; cueId++; retryOf = retry; cueDurationSeconds = cueSeconds
        onset = null; best = null; bestStart = null; latest = null; progressStart = null
        bestEnd = null
        tracker = if (relative && !buffered) RelativeHoldTracker(midi, anchor!!, floor) else null
        bufferedTracker = if (buffered) BufferedHoldTracker(midi, anchor!!, floor, revised = protocolVersion == "guided-range-v7") else null
        displayHold = 0.0; frames.clear()
    }
    private fun segment(reason: String, t: Double): RangeAttempt? {
        val midi = target ?: return null
        // A live checkpoint is not an endpoint: an unobserved penalty tail may follow it.
        // Graceful stop/replay already sealed the tracker before reaching this function.
        val record = bufferedTracker?.snapshot(if (reason == "app_interrupted") "checkpoint" else reason)
        val chosenMetrics = if (buffered) record?.metrics else best
        val hasResponse = !waitingForCue && responseStart != null && t > responseStart!!
        val status = when {
            chosenMetrics?.passed == true -> "passed"
            chosenMetrics != null -> "not_passed"
            latest != null -> "insufficient_quality"
            else -> "unmeasured"
        }
        return RangeAttempt(id + "-" + (attempts.size + 1), midi, phase, cueStart,
            if (hasResponse) cueEnd!! else t, if (hasResponse) responseStart!! else t, t,
            if (buffered) record?.start else bestStart,
            if (buffered) record?.end else if (relative) bestEnd else bestStart?.plus(RangeProtocol.WINDOW), status, chosenMetrics, retryOf, register,
            visitId, reason, progressStart != null, false, progressStart, progressStart?.plus(RangeProtocol.WINDOW),
            when (reason) { "threshold" -> "reached"; "manual" -> "manual_failed"; else -> null },
            (bufferedTracker?.observed ?: tracker?.observed)?.toList().orEmpty(),
            record?.referenceStart, record?.referenceEnd, record?.referenceMidi, record?.endReason,
            record?.pitchCandidates.orEmpty(),
            if (adaptive) probeStage() else null, if (adaptive) anchor else null)
    }
    private fun addAttempt(reason: String, t: Double) {
        bufferedTracker?.seal(reason)
        segment(reason, t)?.let { attempts.add(it) }
    }
    private fun selected(records: List<RangeAttempt>, visit: String?): RangeAttempt? {
        val group = records.filter { it.visitId == visit }
        return group.filter { it.metrics != null }.maxByOrNull {
            if (relative) it.metrics!!.recordedHoldSeconds else it.metrics!!.inTuneRatio
        } ?: group.lastOrNull()
    }
    private fun finalizeVisit(records: MutableList<RangeAttempt>, reason: String): RangeAttempt? {
        val chosen = selected(records, visitId) ?: return null
        val progressed = reason != "anchor_reset" && (if (relative) chosen.metrics?.passed == true else records.any { it.visitId == visitId && it.progressEvaluationStart != null })
        for (i in records.indices) if (records[i].visitId == visitId)
            records[i] = records[i].copy(selectedForVisit = records[i].attemptId == chosen.attemptId,
                advanceReason = reason, progressQualified = progressed,
                progressEvaluationStart = if (relative) null else records[i].progressEvaluationStart,
                progressEvaluationEnd = if (relative) null else records[i].progressEvaluationEnd,
                searchOutcome = if (relative && progressed) "reached" else when (reason) { "threshold" -> "reached"; "manual" -> "manual_failed"; else -> null })
        return records.first { it.attemptId == chosen.attemptId }
    }
    private fun closeVisit(reason: String, t: Double): RangeAttempt? {
        if (target == null) return null
        addAttempt(reason, t)
        val result = finalizeVisit(attempts, reason)
        target = null; waitingForCue = false
        return result
    }
    private fun completeAttempt(reason: String, t: Double) {
        val midi = target ?: return
        val result = closeVisit(reason, t)!!
        val progress = if (relative) result.progressQualified else reason == "threshold"
        message = when {
            buffered && progress -> "인정 유지 기록을 남겼어요. 다음 음도 확인할게요"
            buffered && result.metrics != null -> "가장 오래 인정된 기록을 남기고 다음 음을 확인해요"
            relative && progress -> "실제로 낸 음의 연속 유지 기록을 확인했어요"
            relative && result.metrics != null -> "최장 연속 유지 기록을 남기고 다음 음을 확인해요"
            progress -> "연속 1초를 확인했어요. 다음 음도 확인할게요"
            result.metrics != null -> "최고 유지율을 기록하고 다음 음으로 넘어가요"
            else -> "이번 음은 미측정으로 남기고 다음 음을 확인해요"
        }
        when (phase) {
            "anchor", "central" -> {
                check(!adaptive || progress) { "v6 requires a passed anchor" }
                centralResults[midi] = result
                if (central.isNotEmpty()) { phase = "central"; startAttempt(central.removeFirst(), t) }
                else startSweep(1, t)
            }
            "up", "down" -> { search!!.record(midi, progress); nextSearch(t) }
            "reconfirm", "extension" -> nextRefinement(t)
        }
    }
    private fun startSweep(direction: Int, t: Double) {
        phase = if (direction > 0) "up" else "down"
        val m = anchor!!
        val skip = !adaptive && centralResults[m]?.clientStatus == "passed" && centralResults[m + direction * 4]?.clientStatus == "passed"
        if (skip) {
            val interval = if (direction > 0) listOf(m + 1, m + 3) else listOf(m - 3, m - 1)
            sampling = sampling.copy(skippedCentralIntervals = sampling.skippedCentralIntervals + listOf(interval))
        }
        search = RangeSearch(m, direction, if (adaptive) coarseStep else if (skip) 6 else 2, adaptive = adaptive,
            maxMidpointProbes = if (revisedSearch) 1 else null)
        if (direction > 0) sampling = sampling.copy(upperLastSuccess = if (skip) m + 4 else if (centralResults[m]?.progressQualified == true) m else null)
        else sampling = sampling.copy(lowerLastSuccess = if (skip) m - 4 else if (centralResults[m]?.progressQualified == true) m else null)
        message = if (direction > 0) "두 반음씩 위로 올라가요" else "이제 두 반음씩 아래로 내려가요"
        nextSearch(t)
    }
    private fun nextSearch(t: Double) {
        val sweep = search!!
        while (true) {
            sampling = if (phase == "up") sampling.copy(
                upperEnd = sweep.frontier.coerceAtLeast(sampling.upperLastSuccess ?: anchor!!),
                upperLastSuccess = sweep.lastSuccess ?: sampling.upperLastSuccess,
                upperFirstFailure = sweep.firstFailure, upperBoundaryPair = sweep.boundaryPair)
            else sampling.copy(lowerEnd = sweep.frontier.coerceAtMost(sampling.lowerLastSuccess ?: anchor!!),
                lowerLastSuccess = sweep.lastSuccess ?: sampling.lowerLastSuccess,
                lowerFirstFailure = sweep.firstFailure, lowerBoundaryPair = sweep.boundaryPair)
            val next = sweep.next()
            if (next == null) {
                if (phase == "up") {
                    upper = sweep.boundary!!
                    sampling = sampling.copy(upperEnd = sweep.frontier.coerceAtLeast(sampling.upperLastSuccess ?: anchor!!),
                        upperLastSuccess = sweep.lastSuccess ?: sampling.upperLastSuccess)
                    startSweep(-1, t)
                } else {
                    lower = sweep.boundary!!
                    sampling = sampling.copy(lowerEnd = sweep.frontier.coerceAtMost(sampling.lowerLastSuccess ?: anchor!!),
                        lowerLastSuccess = sweep.lastSuccess ?: sampling.lowerLastSuccess, basicCompleted = true)
                    phase = "profile_ready"; target = null; cueMidi = null; currentMidi = null
                    message = if (measuredNotes().isEmpty()) "측정 자료가 부족해요. 계속 측정해 음별 기록을 남길 수 있어요"
                        else "녹음 구간 확인 중…"
                }
                return
            }
            val cached = centralResults[next]
            if (cached != null) { sweep.record(next, cached.searchOutcome == "reached"); continue }
            if (adaptive) message = when {
                phase == "up" && sweep.stage == "bisect" -> "고음 사이 음 확인"
                phase == "down" && sweep.stage == "bisect" -> "저음 사이 음 확인"
                phase == "up" -> "${coarseStep}반음씩 고음 탐색"
                else -> "${coarseStep}반음씩 저음 탐색"
            }
            startAttempt(next, t); return
        }
    }
    private fun measuredNotes(): Set<Int> = (parent?.notes.orEmpty().filter { it.metrics != null }.map { it.midi } +
        attempts.filter { it.selectedForVisit && it.metrics != null }.map { it.targetMidi }).toSet()

    private fun startRefinement(t: Double) {
        sampling = sampling.copy(refinementStatus = "in_progress")
        // Older saved reports have no target history; retain their existing note fallback.
        val priorTargets = previousCompletedTargets ?: parent?.completedNoteFallback().orEmpty()
        val completedTargets = priorTargets + submission().completedTargets()
        refinement.addAll(RangeRefinement.targets(anchor!!, sampling, if (revisedSearch) completedTargets else measuredNotes(),
            reconfirmBoundaries = !revisedSearch))
        message = "고음·저음 경계의 빈 음부터 추가로 확인해요"
        nextRefinement(t)
    }
    private fun nextRefinement(t: Double) {
        if (refinement.isEmpty()) {
            sampling = sampling.copy(refinementStatus = "completed"); finish("completed", t)
        } else {
            val midi = refinement.removeFirst()
            val old = attempts.firstOrNull { it.selectedForVisit && it.targetMidi == midi }
            phase = if (!revisedSearch && (old != null || parent?.notes?.any { it.midi == midi && it.metrics != null } == true)) "reconfirm" else "extension"
            startAttempt(midi, t, old?.attemptId)
        }
    }
    private fun finish(reason: String, t: Double) {
        closeVisit(reason, t)
        if (phase == "down") lower = reason
        if (phase == "up") upper = reason
        now = t; stopReason = reason; phase = "done"; cueMidi = null
    }
    @Synchronized fun submission(): RangeSubmission {
        val snapshot = attempts.toMutableList()
        if (target != null) {
            segment("app_interrupted", now)?.let { snapshot.add(it) }; finalizeVisit(snapshot, "app_interrupted")
        }
        val count = (parent?.notes.orEmpty().filter { it.metrics != null }.map { it.midi } +
            snapshot.filter { it.selectedForVisit && it.metrics != null }.map { it.targetMidi }).toSet().size
        return RangeSubmission(id, protocolVersion = protocolVersion, mode = if (parent == null) "basic" else "extension",
            parentAnalysisId = parent?.analysisId, startMidi = anchor, noiseRms = noise, durationSeconds = now,
            stopReason = stopReason, lowerBoundaryStatus = lower, upperBoundaryStatus = upper,
            attempts = snapshot, registerEvents = registerEvents.toList(), sampling = sampling.copy(measuredNoteCount = count))
    }
    @Synchronized fun view(): View {
        val cents = target?.let { m -> currentMidi?.let { 100 * (it - m) } }
        val peak = listOfNotNull(selected(attempts, visitId)?.metrics, if (buffered) bufferedTracker?.snapshot("in_progress")?.metrics else best)
            .maxByOrNull { if (relative) it.recordedHoldSeconds else it.inTuneRatio }
        return View(phase, target, currentMidi, cents, now, (displayHold / if (relative) RangeProtocol.FULL_HOLD_SECONDS else 1.0).coerceIn(0.0, 1.0).toFloat(), latest,
            cueId, cueMidi, waitingForCue, message, attempts.toList(), phase == "done", cueDurationSeconds, peak, visitId,
            phase in listOf("profile_ready", "anchor_ready", "comfortable_ready") || waitingForCue || responseStart?.let { now < it } == true,
            sampling.copy(measuredNoteCount = measuredNotes().size),
            bufferedTracker?.referenceMidi, if (buffered) bufferedTracker?.zone else null,
            bufferedTracker?.generation ?: 0, floor, if (adaptive) probeStage() else null, adaptive && phase == "anchor")
    }
    private fun probeStage(): String? = when (phase) {
        "anchor" -> "anchor"
        "up", "down" -> search?.stage
        "reconfirm", "extension" -> "refinement"
        else -> null
    }
}
