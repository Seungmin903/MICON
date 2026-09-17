package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.local.UserStore
import com.micon.app.data.onboarding.*
import com.micon.app.data.voice.VoiceProfileMapper
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.RangeReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceOnboardingState(
    val draftId: String, val revision: Long = 0,
    val range: RangeReport? = null, val recording: SavedRecording? = null,
    val progress: VoiceAnalysisProgress? = null, val busy: Boolean = false,
    val saving: Boolean = false, val saved: Boolean = false, val expired: Boolean = false,
    val error: String? = null, val serviceAvailable: Boolean = false,
    val recovering: Boolean = false,
    val savedReceipt: ProfileSaveReceipt? = null,
) {
    val canSave get() = serviceAvailable && range?.availability == "available" && recording != null &&
        progress?.canSave == true && !busy && !saving && !saved && !expired && !recovering
}

class VoiceOnboardingViewModel(
    val ownerId: Long?,
    private val persistence: VoiceOnboardingPersistence,
    private val gateway: VoiceOnboardingGateway,
    private val now: () -> Long = System::currentTimeMillis,
    private val isCurrentOwner: () -> Boolean = { true },
    private val onDispose: () -> Unit = {},
) : ViewModel() {
    private val restored = persistence.draft()
    private var expiresAtMillis = restored.expiresAtMillis
    private val expired = expiresAtMillis?.let { it <= now() } == true
    private val mutableState = MutableStateFlow(VoiceOnboardingState(
        restored.draftId, restored.revision,
        range = if (expired) null else persistence.range(),
        recording = if (expired) null else persistence.recording(restored.recordingId),
        expired = expired, serviceAvailable = gateway.available,
        error = if (expired) "이전 분석이 만료됐어요. 음역부터 새로 측정해 주세요." else null))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0
    private var disposed = false
    private var serverSessionId = state.value.range?.backendSessionId ?: restored.serverSessionId
    init {
        if (expired && isCurrentOwner()) persistence.clearDraft()
        else if (ownerId != null && serverSessionId != null && isCurrentOwner()) {
            val sessionId = checkNotNull(serverSessionId)
            val draftId = state.value.draftId
            val revision = state.value.revision
            val generation = recoveryGeneration
            fun current() = !disposed && isCurrentOwner() && state.value.draftId == draftId &&
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
                try { gateway.recover(ownerId, sessionId, ::accept)?.let(::accept) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (current()) mutableState.update { it.copy(error = "이전 분석을 확인하지 못했어요. 잠시 후 다시 확인해 주세요.") }
                } finally {
                    if (current()) mutableState.update { it.copy(recovering = false) }
                }
            }
        }
    }

    fun rangeReady(): Boolean {
        if (!isCurrentOwner() || checkExpired()) return false
        val report = persistence.range() ?: return false
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
        if (retry != null && before.progress?.status(retry) != ComponentStatus.FAILED) return
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
            try { accept(gateway.analyze(request, retry, ::accept)) }
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
                val cached = runCatching { persistence.saveProfile(SavedVoiceOnboardingProfile(receipt, request.range, result)) }.isSuccess
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
        persistence.deleteRecording(state.value.recording?.id)
        mutableState.update { it.copy(recording = null, progress = null, busy = false, saved = false, savedReceipt = null,
            revision = it.revision + 1, error = null) }
        persist()
    }

    fun restart() {
        if (state.value.saving || !isCurrentOwner()) return
        stopRecovery()
        job?.cancel()
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
        serverSessionId = null
        mutableState.update { it.copy(revision = it.revision + 1, busy = false, saving = false,
            range = null, recording = null, progress = null) }
        persistence.clearDraft()
    }

    private fun matches(request: VoiceOnboardingRequest) = !disposed && isCurrentOwner() &&
        state.value.draftId == request.draftId && state.value.revision == request.revision

    private fun checkExpired(): Boolean {
        if (expiresAtMillis?.let { it <= now() } == true) {
            mutableState.update { it.copy(expired = true, error = "분석이 만료됐어요. 새로 측정해 주세요.") }
        }
        return state.value.expired
    }

    private fun persist() {
        val current = state.value
        if (!disposed && isCurrentOwner()) persistence.write(VoiceOnboardingDraft(current.draftId, current.revision,
            current.recording?.id, expiresAtMillis, serverSessionId))
    }

    private fun stopRecovery() {
        recoveryGeneration++
        recoveryJob?.cancel()
        mutableState.update { it.copy(recovering = false) }
    }

    override fun onCleared() { disposed = true; recoveryJob?.cancel(); job?.cancel(); onDispose() }
}
