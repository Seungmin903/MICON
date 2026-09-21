package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.local.UserStore
import com.micon.app.data.onboarding.*
import com.micon.app.data.voice.VoiceProfileMapper
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeProtocol
import com.micon.app.range.onboardingPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingRangeStatus { NOT_STARTED, RUNNING, READY, FAILED }

data class VoiceOnboardingState(
    val draftId: String, val revision: Long = 0,
    val range: RangeReport? = null, val recording: SavedRecording? = null,
    val progress: VoiceAnalysisProgress? = null, val busy: Boolean = false,
    val saving: Boolean = false, val saved: Boolean = false, val expired: Boolean = false,
    val error: String? = null, val serviceAvailable: Boolean = false,
    val recovering: Boolean = false,
    val savedReceipt: ProfileSaveReceipt? = null,
    val rangePreview: RangeReport? = null,
    val rangeMeasurementId: String? = null,
    val rangeAnalysisStatus: OnboardingRangeStatus = OnboardingRangeStatus.NOT_STARTED,
    val rangeAnalysisError: String? = null,
    val songGuideCompleted: Boolean = false,
    val songGuideError: String? = null,
    val analysisGuideCompleted: Boolean = false,
    val rangeGuideCompleted: Boolean = false,
    val stageGuideError: String? = null,
) {
    val canSelectSong get() = range != null || rangePreview != null
    val waitingForRange get() = rangeAnalysisStatus == OnboardingRangeStatus.RUNNING
    val needsInitialTimbreAnalysis get() = progress == null || (progress.sessionStatus == VoiceSessionStatus.RANGE_IN_PROGRESS &&
        progress.analysisId == null && progress.ecapa == ComponentStatus.PENDING && progress.byol == ComponentStatus.PENDING)
    val needsNewRecording get() = progress?.sessionStatus == VoiceSessionStatus.INSUFFICIENT_DATA ||
        listOf(progress?.ecapa, progress?.byol).any { it == ComponentStatus.INSUFFICIENT_DATA }
    val analysisInputError get() = if (needsNewRecording) "분석에 쓸 목소리 구간이 부족해요. 노래를 다시 불러 주세요."
        else VoiceOnboardingInput.problem(draftId, range, recording)
    val canRetryAnalysis get() = serviceAvailable && !busy && !saving && !saved && !expired && !recovering &&
        !waitingForRange && rangeAnalysisStatus != OnboardingRangeStatus.FAILED &&
        !needsNewRecording && progress?.sessionStatus?.closed != true
    val canAnalyze get() = canRetryAnalysis && progress?.canSave != true && progress?.analyzing != true &&
        analysisInputError == null
    fun canRetry(component: AnalysisComponent) = canRetryAnalysis && progress?.status(component) == ComponentStatus.FAILED &&
        (component != AnalysisComponent.ARTIST_SEARCH ||
            (progress?.ecapa == ComponentStatus.COMPLETED && progress.byol == ComponentStatus.COMPLETED))
    val canSave get() = serviceAvailable && range?.availability == "available" && recording != null &&
        progress?.canSave == true && !busy && !saving && !saved && !expired && !recovering &&
        !waitingForRange && rangeAnalysisStatus != OnboardingRangeStatus.FAILED
}

class VoiceOnboardingViewModel(
    val ownerId: Long?,
    private val persistence: VoiceOnboardingPersistence,
    private val gateway: VoiceOnboardingGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val isCurrentOwner: () -> Boolean = { true },
    private val onDispose: () -> Unit = {},
    private val rangeAnalysis: OnboardingRangeAnalysis? = null,
) : ViewModel() {
    private val restored = persistence.draft()
    private var expiresAtMillis = restored.expiresAtMillis
    private val expired = expiresAtMillis?.let { it <= now() } == true
    private val restoredPendingRange = if (expired) null else rangeAnalysis?.pending()
    private val restoredRangePreview = restoredPendingRange?.submission?.onboardingPreview()
    private val mutableState = MutableStateFlow(VoiceOnboardingState(
        restored.draftId, restored.revision,
        range = if (expired || restoredPendingRange != null) null else persistence.range(),
        recording = if (expired) null else persistence.recording(restored.recordingId),
        expired = expired, serviceAvailable = gateway.available, rangePreview = restoredRangePreview,
        songGuideCompleted = restored.songGuideCompleted,
        analysisGuideCompleted = restored.analysisGuideCompleted,
        rangeGuideCompleted = restored.rangeGuideCompleted,
        error = if (expired) "이전 분석이 만료됐어요. 음역부터 새로 측정해 주세요." else null))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var recoveryJob: Job? = null
    private var artistJob: Job? = null
    private var rangeJob: Job? = null
    private var rangeGeneration = 0
    private var autoAnalyzedRecordingId: String? = null
    private var recoveryGeneration = 0
    private var disposed = false
    private var serverSessionId = if (restoredPendingRange != null) null else state.value.range?.backendSessionId ?: restored.serverSessionId
    init {
        if (expired && isCurrentOwner()) persistence.clearDraft()
        else if (restoredRangePreview != null) rangeMeasurementSaved(checkNotNull(restoredPendingRange).submission.sessionId)
        else if (ownerId != null && serverSessionId != null && isCurrentOwner()) {
            val sessionId = checkNotNull(serverSessionId)
            val draftId = state.value.draftId
            val revision = state.value.revision
            val generation = recoveryGeneration
            fun current() = !disposed && !state.value.saved && isCurrentOwner() && state.value.draftId == draftId &&
                state.value.revision == revision && serverSessionId == sessionId && recoveryGeneration == generation
            mutableState.update { it.copy(recovering = true) }
            recoveryJob = viewModelScope.launch {
                fun accept(recovered: VoiceAnalysisProgress) {
                    if (!current() || recovered.sessionId != sessionId.toString()) return
                    if (state.value.progress?.let { it.version > recovered.version } == true) return
                    recovered.expiresAtMillis?.let { expiresAtMillis = it }
                    mutableState.update { it.copy(progress = recovered, error = null) }
                    persist()
                    checkExpired()
                }
                try {
                    gateway.recover(ownerId, sessionId, ::accept)?.let { recovered ->
                        accept(recovered)
                        if (current()) continueArtistSearch(recovered, ::accept)
                    }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (current()) mutableState.update { it.copy(error = "이전 분석을 확인하지 못했어요. 잠시 후 다시 확인해 주세요.") }
                } finally {
                    if (current()) mutableState.update { it.copy(recovering = false) }
                }
            }
        }
    }

    /** The next screen needs only a saved eligible measurement; model input remains unavailable. */
    fun rangeMeasurementSaved(measurementId: String): Boolean {
        if (disposed || !isCurrentOwner() || checkExpired() || state.value.saving || state.value.saved) return false
        if (state.value.rangeMeasurementId == measurementId && state.value.canSelectSong) return true
        val pending = rangeAnalysis?.pending()?.takeIf { it.submission.sessionId == measurementId } ?: return false
        val preview = pending.submission.onboardingPreview() ?: return false
        stopRangeAnalysis()
        stopRecovery()
        job?.cancel()
        serverSessionId = null
        mutableState.update { it.copy(range = null, rangePreview = preview, rangeMeasurementId = measurementId,
            rangeAnalysisStatus = OnboardingRangeStatus.NOT_STARTED, rangeAnalysisError = null,
            progress = null, busy = false, error = null, songGuideError = null,
            analysisGuideCompleted = true, rangeGuideCompleted = true, stageGuideError = null,
            songGuideCompleted = restored.songGuideCompleted && it.draftId == restored.draftId &&
                restoredPendingRange?.submission?.sessionId == measurementId) }
        persist()
        startRangeAnalysis(measurementId)
        return true
    }

    fun completeSongGuide(): Boolean {
        val current = state.value
        if (disposed || !isCurrentOwner() || checkExpired() || !current.canSelectSong || current.saving) return false
        if (current.songGuideCompleted) return true
        return try {
            persistence.write(current.toDraft(songGuideCompleted = true))
            mutableState.update { it.copy(songGuideCompleted = true, songGuideError = null) }
            true
        } catch (_: Exception) {
            mutableState.update { it.copy(songGuideError = "진행 상태를 저장하지 못했어요. 다시 시도해 주세요.") }
            false
        }
    }

    fun completeAnalysisGuide(): Boolean = completeStageGuide(analysis = true)

    fun completeRangeGuide(): Boolean = completeStageGuide(analysis = false)

    private fun completeStageGuide(analysis: Boolean): Boolean {
        val current = state.value
        if (disposed || !isCurrentOwner() || checkExpired() || current.saving) return false
        if (analysis && current.analysisGuideCompleted || !analysis && current.rangeGuideCompleted) return true
        return try {
            val next = if (analysis) current.copy(analysisGuideCompleted = true, stageGuideError = null)
                else current.copy(analysisGuideCompleted = true, rangeGuideCompleted = true, stageGuideError = null)
            persistence.write(next.toDraft())
            mutableState.value = next
            true
        } catch (_: Exception) {
            mutableState.update { it.copy(stageGuideError = "진행 상태를 저장하지 못했어요. 다시 시도해 주세요.") }
            false
        }
    }

    fun retryRangeAnalysis() {
        if (state.value.rangeAnalysisStatus != OnboardingRangeStatus.FAILED || !isCurrentOwner() || checkExpired()) return
        state.value.rangeMeasurementId?.let(::startRangeAnalysis)
    }

    private fun startRangeAnalysis(measurementId: String) {
        val analysis = rangeAnalysis ?: return
        if (analysis.pending()?.submission?.sessionId != measurementId) return
        val draftId = state.value.draftId
        val generation = ++rangeGeneration
        fun current() = !disposed && isCurrentOwner() && !state.value.expired && !state.value.saved &&
            state.value.draftId == draftId && state.value.rangeMeasurementId == measurementId && rangeGeneration == generation
        mutableState.update { it.copy(rangeAnalysisStatus = OnboardingRangeStatus.RUNNING, rangeAnalysisError = null) }
        rangeJob = viewModelScope.launch {
            try {
                val report = analysis.submit()
                if (!current() || checkExpired()) return@launch
                check(RangeProtocol.supports(report.protocolVersion) && report.availability == "available" && report.lowMidi != null && report.highMidi != null &&
                    report.lowMidi in 0..127 && report.highMidi in report.lowMidi..127)
                check(if (ownerId == null) report.verification == "device_measured" else
                    report.verification == "model_verified" && (report.backendSessionId ?: 0) > 0) {
                    "검증된 음역 결과를 확인하지 못했어요."
                }
                serverSessionId = report.backendSessionId
                mutableState.update { it.copy(range = report, rangePreview = null,
                    rangeAnalysisStatus = OnboardingRangeStatus.READY, rangeAnalysisError = null, error = null) }
                persist()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (current()) mutableState.update { it.copy(rangeAnalysisStatus = OnboardingRangeStatus.FAILED,
                    rangeAnalysisError = "음역 분석 결과를 확인하지 못했어요. 다시 확인하거나 음역을 다시 측정해 주세요.") }
            }
        }
    }

    private fun stopRangeAnalysis() {
        rangeGeneration++
        rangeJob?.cancel()
    }

    fun analyzeWhenReady() {
        val current = state.value
        val recordingId = current.recording?.id ?: return
        if (ownerId == null || !current.canAnalyze || !current.needsInitialTimbreAnalysis || autoAnalyzedRecordingId == recordingId) return
        autoAnalyzedRecordingId = recordingId
        analyze()
    }

    fun rangeReady(): Boolean {
        if (!isCurrentOwner() || checkExpired()) return false
        val report = persistence.range() ?: return false
        if (report.availability != "available") return false
        stopRecovery()
        serverSessionId = report.backendSessionId
        mutableState.update { it.copy(range = report, error = null) }
        persist()
        return true
    }

    fun recordingReady(recording: SavedRecording) {
        if (!isCurrentOwner() || checkExpired() || recording.meta.onboardingDraftId != state.value.draftId) return
        stopRecovery()
        job?.cancel()
        if (state.value.recording?.id != recording.id) {
            autoAnalyzedRecordingId = null
        }
        mutableState.update { it.copy(recording = recording, revision = it.revision + 1,
            progress = null, busy = false, saving = false, saved = false, savedReceipt = null, error = null) }
        persist()
    }

    fun analyze(retry: AnalysisComponent? = null) {
        val before = state.value
        if (before.busy || before.saving || before.saved || before.recovering || !isCurrentOwner() || checkExpired()) return
        if (!gateway.available) {
            mutableState.update { it.copy(error = VoiceOnboardingNotReady().message) }
            return
        }
        if (ownerId == null) {
            mutableState.update { it.copy(error = "로그인하면 음색 분석 결과를 확인할 수 있어요.") }
            return
        }
        if (retry != null) {
            if (!before.canRetry(retry)) return
        } else if (!before.canAnalyze) {
            before.analysisInputError?.let { message -> mutableState.update { it.copy(error = message) } }
            return
        }
        artistJob?.cancel()
        val range = before.range ?: return
        val recording = before.recording ?: return
        val request = VoiceOnboardingRequest(before.draftId, before.revision + 1, ownerId, range, recording)
        mutableState.update { it.copy(revision = request.revision, busy = true, error = null) }
        persist()
        job = viewModelScope.launch {
            fun accept(incoming: VoiceAnalysisProgress) {
                if (!matches(request) || incoming.sessionId.isBlank() || incoming.version < 0) return
                if (request.range.backendSessionId?.let { it.toString() != incoming.sessionId } == true) return
                val previous = state.value.progress
                if (previous != null && (previous.sessionId != incoming.sessionId || incoming.version < previous.version)) return
                // Full server snapshot. Never invent or increment resultVersion locally.
                incoming.expiresAtMillis?.let { expiresAtMillis = it }
                serverSessionId = incoming.sessionId.toLongOrNull() ?: serverSessionId
                mutableState.update { it.copy(progress = incoming) }
                persist()
                checkExpired()
            }
            try {
                val result = gateway.analyze(request, retry, ::accept)
                accept(result)
                if (matches(request)) continueArtistSearch(result, ::accept)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (matches(request)) mutableState.update { it.copy(error =
                    if (e is VoiceOnboardingNotReady) e.message else "분석을 확인하지 못했어요. 잠시 후 다시 확인해 주세요.") }
            } finally {
                if (matches(request)) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelAnalysis() {
        if (!isCurrentOwner() || state.value.saving) return
        if (state.value.waitingForRange) {
            stopRangeAnalysis()
            mutableState.update { it.copy(rangeAnalysisStatus = OnboardingRangeStatus.FAILED,
                rangeAnalysisError = "음역 분석 확인을 중단했어요. 측정 녹음은 보관되어 있어요.") }
            return
        }
        stopRecovery()
        job?.cancel()
        val result = state.value.progress
        if (ownerId == null || result?.analysisId == null) {
            mutableState.update { it.copy(revision = it.revision + 1, busy = false,
                error = "분석 확인을 중단했어요.") }
            persist()
            return
        }
        val beforeCancel = state.value
        mutableState.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            val failure = try { gateway.cancel(ownerId, result); null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { e }
            if (disposed || !isCurrentOwner() || state.value.draftId != beforeCancel.draftId ||
                state.value.revision != beforeCancel.revision) return@launch
            mutableState.update { it.copy(revision = it.revision + 1, busy = false,
                error = if (failure == null) "분석을 취소했어요." else "분석 취소를 확인하지 못했어요.") }
            persist()
        }
    }

    fun save() {
        val current = state.value
        if (ownerId == null || !current.canSave || !isCurrentOwner() || checkExpired()) return
        val request = VoiceOnboardingRequest(current.draftId, current.revision, ownerId,
            checkNotNull(current.range), checkNotNull(current.recording))
        val result = checkNotNull(current.progress)
        mutableState.update { it.copy(saving = true, error = null) }
        job = viewModelScope.launch {
            try {
                val receipt = gateway.save(request, result)
                if (!matches(request) || checkExpired()) return@launch
                check(receipt.sessionId == result.sessionId && receipt.version == result.version && receipt.profileId.isNotBlank())
                // Remote success is authoritative even if refreshing the local cache fails.
                artistJob?.cancel()
                val latestResult = state.value.progress?.takeIf {
                    it.sessionId == result.sessionId && it.version == result.version
                } ?: result
                val cached = runCatching { persistence.saveProfile(SavedVoiceOnboardingProfile(receipt, request.range, latestResult)) }.isSuccess
                UserStore.setProfile(VoiceProfileMapper.fromOnboarding(request.range, now()))   // 연습·추천 상세가 바로 새 음역을 본다
                runCatching { persistence.clearDraft() }
                mutableState.update { it.copy(saved = true, saving = false, savedReceipt = receipt,
                    error = if (cached) null else "서버에 저장됐지만 기기의 결과를 갱신하지 못했어요.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (matches(request)) mutableState.update { it.copy(
                    error = "저장 완료를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.", saving = false) }
            } finally {
                if (matches(request)) mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun newRecording() {
        if (state.value.saving || !isCurrentOwner()) return
        stopRecovery()
        job?.cancel()
        autoAnalyzedRecordingId = null
        persistence.deleteRecording(state.value.recording?.id)
        mutableState.update { it.copy(recording = null, progress = null, busy = false, saved = false, savedReceipt = null,
            revision = it.revision + 1, error = null) }
        persist()
    }

    fun restart() {
        if (state.value.saving || !isCurrentOwner()) return
        stopRecovery()
        job?.cancel()
        stopRangeAnalysis()
        autoAnalyzedRecordingId = null
        val draft = persistence.startNewDraft()
        expiresAtMillis = null
        serverSessionId = null
        mutableState.value = VoiceOnboardingState(draft.draftId, serviceAvailable = gateway.available)
        persist()
    }

    fun discard() {
        if (!isCurrentOwner()) return
        stopRecovery()
        job?.cancel()
        stopRangeAnalysis()
        autoAnalyzedRecordingId = null
        serverSessionId = null
        mutableState.update { it.copy(revision = it.revision + 1, busy = false, saving = false,
            range = null, rangePreview = null, rangeMeasurementId = null,
            rangeAnalysisStatus = OnboardingRangeStatus.NOT_STARTED, rangeAnalysisError = null,
            recording = null, progress = null, songGuideCompleted = false, songGuideError = null) }
        persistence.clearDraft()
    }

    private fun matches(request: VoiceOnboardingRequest) = !disposed && !state.value.saved && isCurrentOwner() &&
        state.value.draftId == request.draftId && state.value.revision == request.revision

    private fun checkExpired(): Boolean {
        if (expiresAtMillis?.let { it <= now() } == true) {
            mutableState.update { it.copy(expired = true, error = "분석이 만료됐어요. 새로 측정해 주세요.") }
        }
        return state.value.expired
    }

    private fun persist() {
        val current = state.value
        if (!disposed && isCurrentOwner()) persistence.write(current.toDraft())
    }

    private fun VoiceOnboardingState.toDraft(
        songGuideCompleted: Boolean = this.songGuideCompleted,
    ) = VoiceOnboardingDraft(
        draftId = draftId,
        revision = revision,
        recordingId = recording?.id,
        expiresAtMillis = expiresAtMillis,
        serverSessionId = serverSessionId,
        songGuideCompleted = songGuideCompleted,
        analysisGuideCompleted = analysisGuideCompleted,
        rangeGuideCompleted = rangeGuideCompleted,
    )

    private fun stopRecovery() {
        recoveryGeneration++
        recoveryJob?.cancel()
        artistJob?.cancel()
        mutableState.update { it.copy(recovering = false) }
    }

    private fun continueArtistSearch(result: VoiceAnalysisProgress, accept: (VoiceAnalysisProgress) -> Unit) {
        if (ownerId == null || !result.awaitingArtistResult) return
        val current = state.value.progress ?: return
        if (current.sessionId != result.sessionId || current.version > result.version) return
        artistJob?.cancel()
        artistJob = viewModelScope.launch {
            try { accept(gateway.awaitArtists(ownerId, result, accept)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Re-entering the report resumes the independent artist lookup. */ }
        }
    }

    override fun onCleared() { disposed = true; recoveryJob?.cancel(); artistJob?.cancel(); rangeJob?.cancel();
        job?.cancel(); onDispose() }
}
