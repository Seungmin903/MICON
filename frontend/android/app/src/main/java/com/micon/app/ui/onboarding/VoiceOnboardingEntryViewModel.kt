package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.data.onboarding.VoiceOnboardingPersistence
import com.micon.app.data.onboarding.hasResumableDraft
import com.micon.app.data.onboarding.startNewDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoiceOnboardingEntryState(
    val choosing: Boolean = false, val busy: Boolean = false,
    val navigate: Boolean = false, val error: String? = null,
)

/** A null store is a guest entry, with no member draft to resume or delete. */
class VoiceOnboardingEntryViewModel(
    private val persistence: VoiceOnboardingPersistence?,
    private val isCurrentOwner: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VoiceOnboardingEntryState())
    val state = mutableState.asStateFlow()

    fun open() {
        if (state.value.choosing) return
        prepare {
            if (persistence?.hasResumableDraft(now()) == true) VoiceOnboardingEntryState(choosing = true)
            else {
                persistence?.startNewDraft()
                VoiceOnboardingEntryState(navigate = true)
            }
        }
    }

    fun resume() {
        if (!state.value.choosing) return
        prepare {
            // The draft may have expired while the choice was visible.
            if (persistence?.hasResumableDraft(now()) != true) persistence?.startNewDraft()
            VoiceOnboardingEntryState(navigate = true)
        }
    }

    fun startNew() {
        if (!state.value.choosing) return
        prepare {
            persistence?.startNewDraft()
            VoiceOnboardingEntryState(navigate = true)
        }
    }

    fun dismiss() {
        if (!state.value.busy) mutableState.value = VoiceOnboardingEntryState()
    }

    fun navigationHandled() { mutableState.value = VoiceOnboardingEntryState() }

    private fun prepare(action: () -> VoiceOnboardingEntryState) {
        if (state.value.busy || state.value.navigate || !isCurrentOwner()) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val prepared = withContext(io) { if (isCurrentOwner()) action() else null }
                mutableState.value = if (isCurrentOwner() && prepared != null) prepared else VoiceOnboardingEntryState()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(busy = false,
                    error = "이전 측정 기록을 확인하지 못했어요. 다시 시도해 주세요.") }
            }
        }
    }
}
