package com.micon.app.ui.onboarding

import com.micon.app.data.onboarding.*
import com.micon.app.data.singanalysis.*
import com.micon.app.range.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import androidx.lifecycle.ViewModelStore

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun range() = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
        "completed", "measured", "measured", "available", verification = "device_measured")

    private fun recording(draftId: String = "local-draft") = SavedRecording(SingRecordingMeta(
        id = "recording", songId = 1, title = "Song", artist = "Artist", scope = "FULL",
        planSource = "SONG_START", plannedStartSec = 0.0, plannedEndSec = 100.0,
        sungStartSec = 0.0, sungEndSec = 35.0, endedEarly = true, recordedAt = 0,
        sampleRate = 16000, durationSec = 35.0, lyricTiming = "SYNCED", wavFileName = "recording.wav",
        onboardingDraftId = draftId), File("recording.wav"), File("recording.json"))

    private fun ready() = VoiceAnalysisProgress("server-session", 42, ComponentStatus.COMPLETED,
        ComponentStatus.COMPLETED, ComponentStatus.COMPLETED,
        sessionStatus = VoiceSessionStatus.READY_TO_SAVE, saveAllowed = true, rangeStatus = "AVAILABLE")

    private inner class Store : VoiceOnboardingPersistence {
        var data = VoiceOnboardingDraft("local-draft", recordingId = "recording")
        var audio: SavedRecording? = recording()
        var report: RangeReport? = this@VoiceOnboardingViewModelTest.range()
        var profile: SavedVoiceOnboardingProfile? = null
        var failCache = false
        var clears = 0
        override fun draft() = data
        override fun write(draft: VoiceOnboardingDraft) { data = draft }
        override fun range() = report
        override fun recording(id: String?) = audio?.takeIf { it.id == id }
        override fun deleteRecording(id: String?) { if (audio?.id == id) audio = null }
        override fun saveProfile(profile: SavedVoiceOnboardingProfile) {
            if (failCache) error("disk failure")
            this.profile = profile
        }
        override fun clearDraft() { clears++; audio = null; report = null }
    }

    private inner class Gateway : VoiceOnboardingGateway {
        override var available = true
        var update: ((VoiceAnalysisProgress) -> Unit)? = null
        var request: VoiceOnboardingRequest? = null
        var result = ready()
        var retry: AnalysisComponent? = null
        var analyses = 0
        var saves = 0
        var savedResult: VoiceAnalysisProgress? = null
        var saveFailure = false
        var saveWait: CompletableDeferred<Unit>? = null
        var recovered: VoiceAnalysisProgress? = null
        val recoveredSessions = mutableListOf<Long>()
        var recoveryWait: CompletableDeferred<Unit>? = null
        var recoveryUpdate: ((VoiceAnalysisProgress) -> Unit)? = null
        var cancels = 0
        var cancelWait: CompletableDeferred<Unit>? = null
        override suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent?,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress {
            analyses++; this.request = request; this.retry = retry; update = onProgress
            return result
        }
        override suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt {
            saves++; savedResult = result; saveWait?.await()
            if (saveFailure) error("offline")
            return ProfileSaveReceipt(result.sessionId, result.version, "profile")
        }
        override suspend fun recover(ownerId: Long, sessionId: Long,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress? {
            recoveredSessions += sessionId
            recoveryUpdate = onProgress
            withContext(NonCancellable) { recoveryWait?.await() }
            recovered?.let(onProgress)
            return recovered
        }
        override suspend fun cancel(ownerId: Long, result: VoiceAnalysisProgress) {
            cancels++
            withContext(NonCancellable) { cancelWait?.await() }
        }
    }

    @Test fun restoresActiveServerSessionForSignedInOwner() = runTest {
        val gateway = Gateway().apply { recovered = ready().copy(sessionId = "7", analysisId = 77) }
        val store = Store().apply { data = data.copy(serverSessionId = 7) }
        val model = VoiceOnboardingViewModel(1, store, gateway)
        advanceUntilIdle()
        assertEquals("7", model.state.value.progress?.sessionId)
        assertEquals(77L, model.state.value.progress?.analysisId)
        assertEquals(listOf(7L), gateway.recoveredSessions)
        assertFalse(model.state.value.recovering)
    }

    @Test fun freshDraftNeverRecoversAnUnrelatedServerSession() = runTest {
        val gateway = Gateway().apply { recovered = ready() }
        val store = Store().apply { report = null; audio = null; data = VoiceOnboardingDraft("fresh") }
        val model = VoiceOnboardingViewModel(1, store, gateway)
        advanceUntilIdle()
        assertTrue(gateway.recoveredSessions.isEmpty())
        assertNull(model.state.value.range)
        assertNull(model.state.value.progress)
    }

    @Test fun legacyRangePinsRecoveryWithoutDependingOnLatestActiveSession() = runTest {
        val gateway = Gateway().apply { recovered = ready().copy(sessionId = "7") }
        val store = Store().apply { report = range()!!.copy(backendSessionId = 7) }
        VoiceOnboardingViewModel(1, store, gateway)
        advanceUntilIdle()
        assertEquals(listOf(7L), gateway.recoveredSessions)
        assertEquals(7L, store.data.serverSessionId)
    }

    @Test fun restartRejectsLateRecoveryAndStaysFreshAfterProcessRecreation() = runTest {
        val gateway = Gateway().apply {
            recovered = ready().copy(sessionId = "7")
            recoveryWait = CompletableDeferred()
        }
        val store = Store().apply { data = data.copy(serverSessionId = 7) }
        val model = VoiceOnboardingViewModel(1, store, gateway)
        runCurrent()
        model.restart()
        val newDraft = store.data.draftId
        gateway.recoveryWait!!.complete(Unit)
        advanceUntilIdle()
        assertNotEquals("local-draft", newDraft)
        assertNull(model.state.value.progress)
        assertNull(store.data.serverSessionId)
        assertFalse(model.state.value.recovering)
        val recreated = VoiceOnboardingViewModel(1, store, gateway)
        advanceUntilIdle()
        assertEquals(newDraft, recreated.state.value.draftId)
        assertNull(recreated.state.value.range)
        assertNull(recreated.state.value.recording)
        assertEquals(listOf(7L), gateway.recoveredSessions)
    }

    @Test fun recoveryFromWrongSessionOrChangedOwnerCannotReplaceTheDraft() = runTest {
        var currentOwner = true
        val gateway = Gateway().apply { recovered = ready().copy(sessionId = "8") }
        val store = Store().apply { data = data.copy(serverSessionId = 7) }
        val model = VoiceOnboardingViewModel(1, store, gateway, isCurrentOwner = { currentOwner })
        advanceUntilIdle()
        assertNull(model.state.value.progress)
        currentOwner = false
        gateway.recoveryUpdate!!(ready().copy(sessionId = "7"))
        model.restart()
        assertNull(model.state.value.progress)
        assertEquals(0, store.clears)
    }

    @Test fun newRecordingRejectsLateRecoveryFromPreviousRecording() = runTest {
        val gateway = Gateway().apply { recoveryWait = CompletableDeferred(); recovered = ready().copy(sessionId = "7") }
        val model = VoiceOnboardingViewModel(1, Store().apply { data = data.copy(serverSessionId = 7) }, gateway)
        runCurrent()
        model.newRecording()
        gateway.recoveryWait!!.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.progress)
        assertNull(model.state.value.recording)
    }

    @Test fun leavingOnboardingRejectsLateAnalysisBeforeNewEntry() = runTest {
        val gateway = Gateway()
        val store = Store()
        val model = VoiceOnboardingViewModel(1, store, gateway)
        val models = ViewModelStore().apply { put("onboarding", model) }
        model.analyze(); advanceUntilIdle()
        models.clear()
        val fresh = store.startNewDraft()
        gateway.update!!(ready().copy(version = 43))
        assertEquals(fresh, store.data)
    }

    @Test fun leavingDuringCancellationCannotRewriteTheNextDraft() = runTest {
        val gateway = Gateway().apply { result = ready().copy(analysisId = 77); cancelWait = CompletableDeferred() }
        val store = Store()
        val model = VoiceOnboardingViewModel(1, store, gateway)
        val models = ViewModelStore().apply { put("onboarding", model) }
        model.analyze(); advanceUntilIdle()
        model.cancelAnalysis(); runCurrent()
        models.clear()
        val fresh = store.startNewDraft()
        gateway.cancelWait!!.complete(Unit); advanceUntilIdle()
        assertEquals(fresh, store.data)
    }

    @Test fun cancelStopsRemoteAnalysisWhenServerJobIsKnown() = runTest {
        val gateway = Gateway().apply { result = ready().copy(analysisId = 77) }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        model.cancelAnalysis(); advanceUntilIdle()
        assertEquals(1, gateway.cancels)
        assertTrue(model.state.value.error!!.contains("취소"))
    }

    @Test fun pendingServiceKeepsMeasuredRangeWithoutSendingOrInventingJobs() = runTest {
        val gateway = Gateway().apply { available = false }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); model.save(); advanceUntilIdle()
        assertEquals(48, model.state.value.range?.lowMidi)
        assertNull(model.state.value.progress)
        assertEquals(0, gateway.analyses)
        assertEquals(0, gateway.saves)
        assertFalse(model.state.value.canSave)
        assertTrue(model.state.value.error!!.contains("준비 중"))
    }

    @Test fun partialResultCannotSaveEvenIfSaveFlagIsInconsistent() = runTest {
        val gateway = Gateway().apply { result = ready().copy(
            byol = ComponentStatus.RUNNING, sessionStatus = VoiceSessionStatus.PARTIAL_READY) }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        assertEquals(48, model.state.value.range?.lowMidi)
        assertFalse(model.state.value.canSave)
        model.save(); advanceUntilIdle()
        assertEquals(0, gateway.saves)
    }

    @Test fun allJobsCompletedDoesNotOverrideBackendSaveDenial() = runTest {
        val gateway = Gateway().apply { result = ready().copy(saveAllowed = false) }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        assertFalse(model.state.value.canSave)
        model.save(); advanceUntilIdle()
        assertEquals(0, gateway.saves)
    }

    @Test fun allowedArtistFailureUsesRealSessionAndVersionWhenSaving() = runTest {
        val gateway = Gateway().apply { result = ready().copy(artistSearch = ComponentStatus.FAILED) }
        val store = Store()
        val model = VoiceOnboardingViewModel(1, store, gateway)
        model.analyze(); advanceUntilIdle()
        assertTrue(model.state.value.canSave)
        assertEquals("local-draft", gateway.request!!.draftId)
        model.save(); advanceUntilIdle()
        assertTrue(model.state.value.saved)
        assertEquals("server-session", store.profile?.receipt?.sessionId)
        assertEquals(42L, store.profile?.receipt?.version)
    }

    @Test fun guestCannotSaveEvenWhenAllResultsSucceed() = runTest {
        val gateway = Gateway()
        val store = Store()
        val model = VoiceOnboardingViewModel(null, store, gateway)
        model.analyze(); advanceUntilIdle()
        model.save(); advanceUntilIdle()
        assertEquals(0, gateway.saves)
        assertNull(store.profile)
        model.discard()
        assertNull(model.state.value.range)
        assertNull(model.state.value.recording)
    }

    @Test fun retryTargetsOnlyFailedComponentAndKeepsSuccessfulSnapshot() = runTest {
        val gateway = Gateway().apply { result = ready().copy(byol = ComponentStatus.FAILED, saveAllowed = false) }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        model.analyze(AnalysisComponent.ECAPA); advanceUntilIdle()
        assertEquals(1, gateway.analyses)
        gateway.result = ready()
        model.analyze(AnalysisComponent.SINGER_BYOL); advanceUntilIdle()
        assertEquals(AnalysisComponent.SINGER_BYOL, gateway.retry)
        assertEquals(RetryTarget.BYOL, gateway.retry!!.retryTarget)
        assertEquals(ComponentStatus.COMPLETED, model.state.value.progress?.ecapa)
        assertTrue(model.state.value.canSave)
    }

    @Test fun insufficientDataDoesNotRetryTheSameRecordingAsSystemFailure() = runTest {
        val gateway = Gateway().apply { result = ready().copy(byol = ComponentStatus.INSUFFICIENT_DATA) }
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        model.analyze(AnalysisComponent.SINGER_BYOL); advanceUntilIdle()
        assertEquals(1, gateway.analyses)
        assertFalse(model.state.value.canSave)
    }

    @Test fun lateCallbackCannotRestoreDiscardedRecording() = runTest {
        val gateway = Gateway()
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        model.newRecording()
        gateway.update!!(ready())
        assertNull(model.state.value.progress)
        assertNull(model.state.value.recording)
    }

    @Test fun oldVersionAndDifferentSessionCannotOverwriteCurrentResult() = runTest {
        val gateway = Gateway()
        val model = VoiceOnboardingViewModel(1, Store(), gateway)
        model.analyze(); advanceUntilIdle()
        gateway.update!!(ready().copy(version = 41, byol = ComponentStatus.FAILED))
        gateway.update!!(ready().copy(sessionId = "other", version = 43))
        assertEquals(ready(), model.state.value.progress)
        assertNotEquals(model.state.value.revision, model.state.value.progress!!.version)
    }

    @Test fun saveDoubleTapAndFailureKeepPreviousProfileUntilRetrySucceeds() = runTest {
        val gateway = Gateway().apply { saveFailure = true; saveWait = CompletableDeferred() }
        val store = Store()
        val old = SavedVoiceOnboardingProfile(ProfileSaveReceipt("old", 0, "old"), range(), ready())
        store.profile = old
        val model = VoiceOnboardingViewModel(1, store, gateway)
        model.analyze(); advanceUntilIdle()
        model.save(); model.save(); runCurrent()
        assertEquals(1, gateway.saves)
        gateway.saveWait!!.complete(Unit); advanceUntilIdle()
        assertSame(old, store.profile)
        assertFalse(model.state.value.saved)
        gateway.saveFailure = false
        model.save(); advanceUntilIdle()
        assertEquals(2, gateway.saves)
        assertTrue(model.state.value.saved)
    }

    @Test fun successfulServerSaveIsNotReportedAsFailureWhenCacheWriteFails() = runTest {
        val gateway = Gateway()
        val model = VoiceOnboardingViewModel(1, Store().apply { failCache = true }, gateway)
        model.analyze(); advanceUntilIdle()
        model.save(); advanceUntilIdle()
        assertTrue(model.state.value.saved)
        assertTrue(model.state.value.error!!.contains("서버에 저장"))
    }

    @Test fun changedAccountRejectsResultsAndSave() = runTest {
        var current = true
        val gateway = Gateway()
        val model = VoiceOnboardingViewModel(1, Store(), gateway, isCurrentOwner = { current })
        model.analyze(); advanceUntilIdle()
        current = false
        gateway.update!!(ready().copy(version = 43))
        model.save(); advanceUntilIdle()
        assertEquals(0, gateway.saves)
        assertEquals(42L, model.state.value.progress?.version)
    }

    @Test fun onlyExplicitExpiryInvalidatesRestoredDraft() = runTest {
        val store = Store().apply { data = data.copy(expiresAtMillis = 10) }
        val model = VoiceOnboardingViewModel(1, store, Gateway(), now = { 11 })
        assertTrue(model.state.value.expired)
        assertFalse(model.rangeReady())
        assertNull(model.state.value.range)
        assertEquals(1, store.clears)
        assertFalse(VoiceOnboardingViewModel(1, Store(), Gateway(), now = { Long.MAX_VALUE }).state.value.expired)
    }

    @Test fun recordingFromDifferentLocalDraftIsRejected() {
        val model = VoiceOnboardingViewModel(1, Store(), Gateway())
        val prior = model.state.value.recording
        model.recordingReady(recording("other-draft"))
        assertSame(prior, model.state.value.recording)
    }

    @Test fun singersAreUniqueRankedAndBoundedWithoutInvalidIds() {
        val artists = (1L..9L).map { SimilarVoiceArtist(it, "Singer", null, it / 10.0) } +
            SimilarVoiceArtist(9, "Duplicate", null, 0.8) + SimilarVoiceArtist(0, "Invalid", null, 1.0)
        val result = VoiceAnalysisProgress("s", 1, artists = artists).visibleArtists
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), result.map { it.artistId })
    }
}
