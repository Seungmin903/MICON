package com.micon.app.ui.onboarding

import androidx.lifecycle.ViewModelStore
import com.micon.app.data.onboarding.*
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRangeContinuationTest {
    @get:Rule val files = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var modelCount = 0
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }

    private fun measurement(id: String = "measurement") = PendingRange(usableRangeSubmission().copy(sessionId = id))
    private fun verified(sessionId: Long = 7) = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(60, "passed")),
        60, 72, emptyList(), "completed", "FOUND", "FOUND", "available",
        verification = "model_verified", backendSessionId = sessionId)

    private class Store : VoiceOnboardingPersistence {
        var data = VoiceOnboardingDraft("draft")
        var report: RangeReport? = null
        var audio: SavedRecording? = null
        override fun draft() = data
        override fun write(draft: VoiceOnboardingDraft) { data = draft }
        override fun range() = report
        override fun recording(id: String?) = audio?.takeIf { it.id == id }
        override fun deleteRecording(id: String?) { if (audio?.id == id) audio = null }
        override fun saveProfile(profile: SavedVoiceOnboardingProfile) = error("unused")
        override fun clearDraft() { report = null; audio = null }
    }
    private class RangeWork : OnboardingRangeAnalysis {
        var saved: PendingRange? = null
        var response = CompletableDeferred<RangeReport>()
        var ignoreCancellation = false
        val requests = mutableListOf<String>()
        override fun pending() = saved
        override suspend fun submit(): RangeReport {
            requests += checkNotNull(saved).submission.sessionId
            val response = response
            return if (ignoreCancellation) withContext(NonCancellable) { response.await() } else response.await()
        }
    }
    private class Timbre : VoiceOnboardingGateway {
        override val available = true
        val requests = mutableListOf<VoiceOnboardingRequest>()
        var failure = false
        var recoveries = 0
        var recovered: VoiceAnalysisProgress? = null
        override suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent?,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress {
            requests += request
            if (failure) error("offline")
            return VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED, byol = ComponentStatus.COMPLETED,
                artistSearch = ComponentStatus.COMPLETED, rangeStatus = "AVAILABLE", saveAllowed = true,
                sessionStatus = VoiceSessionStatus.READY_TO_SAVE)
        }
        override suspend fun recover(ownerId: Long, sessionId: Long,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress? { recoveries++; return recovered }
        override suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt = error("unused")
    }
    private fun model(store: Store, timbre: Timbre, work: RangeWork, owner: Long? = 1,
        currentOwner: () -> Boolean = { true }) = VoiceOnboardingViewModel(owner, store, timbre,
        isCurrentOwner = currentOwner, rangeAnalysis = work).also { models.put("model-${modelCount++}", it) }
    private fun audio(draftId: String) = onboardingRecording(files.newFile(), draftId)

    @Test fun eligibleSavedMeasurementCanSelectSongsWithoutWaitingAndStartsOnlyOnce() = runTest {
        val work = RangeWork()
        val model = model(Store(), Timbre(), work)
        work.saved = measurement()
        assertTrue(model.rangeMeasurementSaved("measurement"))
        assertTrue(model.rangeMeasurementSaved("measurement"))
        runCurrent()
        assertEquals(listOf("measurement"), work.requests)
        assertTrue(model.state.value.canSelectSong)
        assertTrue(model.state.value.waitingForRange)
        assertEquals("song-guide", onboardingInitialDestination(model.state.value))
        assertEquals("device_measured", model.state.value.rangePreview?.verification)
        assertNull(model.state.value.range)
        assertFalse(model.state.value.canAnalyze)
        work.response.complete(verified())
        advanceUntilIdle()
        assertEquals(OnboardingRangeStatus.READY, model.state.value.rangeAnalysisStatus)
        assertNull(model.state.value.rangePreview)
    }

    @Test fun acknowledgedGuideSurvivesPendingRangeRestorationAndANewMeasurementResetsIt() = runTest {
        val store = Store().apply { data = data.copy(songGuideCompleted = true) }
        val work = RangeWork().apply { saved = measurement() }
        val model = model(store, Timbre(), work)
        runCurrent()
        assertTrue(model.state.value.waitingForRange)
        assertTrue(model.state.value.songGuideCompleted)
        assertEquals("pick", onboardingInitialDestination(model.state.value))
        work.saved = measurement("new-measurement")
        assertTrue(model.rangeMeasurementSaved("new-measurement"))
        assertFalse(model.state.value.songGuideCompleted)
        assertFalse(store.data.songGuideCompleted)
        assertEquals("song-guide", onboardingInitialDestination(model.state.value))
    }

    @Test fun recordingBeforeRangeWaitsForVerifiedInputAndUsesTheSameServerSessionOnce() = runTest {
        val work = RangeWork().apply { saved = measurement() }
        val timbre = Timbre()
        val model = model(Store(), timbre, work)
        model.recordingReady(audio("draft"))
        model.analyzeWhenReady(); model.analyze()
        runCurrent()
        assertTrue(timbre.requests.isEmpty())
        work.response.complete(verified())
        advanceUntilIdle()
        model.analyzeWhenReady(); model.analyzeWhenReady()
        advanceUntilIdle()
        model.analyzeWhenReady()
        assertEquals(1, timbre.requests.size)
        assertEquals(7L, timbre.requests.single().range.backendSessionId)
        assertEquals("model_verified", timbre.requests.single().range.verification)
        assertEquals(7L, model.state.value.range?.backendSessionId)
    }

    @Test fun rangeBeforeRecordingAlsoStartsTimbreOnlyOnce() = runTest {
        val work = RangeWork().apply { saved = measurement(); response.complete(verified()) }
        val timbre = Timbre()
        val model = model(Store(), timbre, work)
        advanceUntilIdle()
        model.analyzeWhenReady()
        assertTrue(timbre.requests.isEmpty())
        model.recordingReady(audio("draft"))
        model.analyzeWhenReady(); model.analyzeWhenReady()
        advanceUntilIdle()
        assertEquals(1, timbre.requests.size)
    }

    @Test fun failedRangeCanRetryTheSameMeasurementWithoutLosingTheRecording() = runTest {
        val work = RangeWork().apply { saved = measurement(); response.completeExceptionally(java.io.IOException("offline")) }
        val timbre = Timbre()
        val model = model(Store(), timbre, work)
        val recording = audio("draft")
        model.recordingReady(recording)
        advanceUntilIdle()
        assertEquals(OnboardingRangeStatus.FAILED, model.state.value.rangeAnalysisStatus)
        assertTrue(model.state.value.canSelectSong)
        model.analyzeWhenReady()
        assertTrue(timbre.requests.isEmpty())
        work.response = CompletableDeferred()
        model.retryRangeAnalysis(); model.retryRangeAnalysis()
        runCurrent()
        assertEquals(listOf("measurement", "measurement"), work.requests)
        assertSame(recording, model.state.value.recording)
        work.response.complete(verified())
        advanceUntilIdle()
        model.analyzeWhenReady()
        advanceUntilIdle()
        assertEquals(1, timbre.requests.size)
    }

    @Test fun restoredPendingMeasurementTakesPriorityOverOldRangeAndSession() = runTest {
        val work = RangeWork().apply { saved = measurement() }
        val timbre = Timbre()
        val store = Store().apply { report = verified(99); data = data.copy(serverSessionId = 99) }
        val model = model(store, timbre, work)
        runCurrent()
        assertTrue(model.state.value.canSelectSong)
        assertNull(model.state.value.range)
        assertEquals(0, timbre.recoveries)
        assertEquals(listOf("measurement"), work.requests)
        work.response.complete(verified())
        advanceUntilIdle()
        assertEquals(7L, store.data.serverSessionId)
    }

    @Test fun incompleteInterruptedOrMissingLocalSavesCannotAdvance() = runTest {
        val work = RangeWork()
        val model = model(Store(), Timbre(), work)
        assertFalse(model.rangeMeasurementSaved("measurement"))
        for (submission in listOf(measurement().submission.copy(sampling = RangeSampling(basicCompleted = false, measuredNoteCount = 1)),
            measurement().submission.copy(stopReason = "app_interrupted"), measurement().submission.copy(attempts = emptyList()))) {
            work.saved = PendingRange(submission)
            assertFalse(model.rangeMeasurementSaved("measurement"))
        }
        assertFalse(model.state.value.canSelectSong)
        assertTrue(work.requests.isEmpty())
    }

    @Test fun restartedDraftRejectsAnOldResultEvenWhenTheTransportIgnoresCancellation() = runTest {
        val work = RangeWork().apply { saved = measurement(); ignoreCancellation = true }
        val model = model(Store(), Timbre(), work)
        runCurrent()
        val oldResponse = work.response
        model.restart()
        work.saved = measurement("new-measurement")
        work.response = CompletableDeferred()
        assertTrue(model.rangeMeasurementSaved("new-measurement"))
        runCurrent()
        oldResponse.complete(verified(99))
        runCurrent()
        assertNull(model.state.value.range)
        assertEquals("new-measurement", model.state.value.rangeMeasurementId)
        assertTrue(model.state.value.waitingForRange)
        work.response.complete(verified())
        advanceUntilIdle()
        assertEquals(7L, model.state.value.range?.backendSessionId)
    }

    @Test fun changedAccountCannotAcceptLateRangeResults() = runTest {
        var currentOwner = true
        val work = RangeWork().apply { saved = measurement() }
        val store = Store()
        val model = model(store, Timbre(), work, currentOwner = { currentOwner })
        runCurrent()
        currentOwner = false
        work.response.complete(verified())
        advanceUntilIdle()
        assertNull(model.state.value.range)
        assertNull(store.data.serverSessionId)
    }

    @Test fun memberCannotUseADevicePreviewAsVerifiedModelInput() = runTest {
        val work = RangeWork().apply { saved = measurement(); response.complete(measurement().submission.deviceReport()) }
        val timbre = Timbre()
        val model = model(Store(), timbre, work)
        model.recordingReady(audio("draft"))
        advanceUntilIdle()
        assertEquals(OnboardingRangeStatus.FAILED, model.state.value.rangeAnalysisStatus)
        model.analyzeWhenReady()
        assertTrue(timbre.requests.isEmpty())
    }

    @Test fun guestLocalSummaryCanAdvanceButNeverStartsTimbreNetworkAnalysis() = runTest {
        val work = RangeWork().apply { saved = measurement(); response.complete(measurement().submission.deviceReport()) }
        val timbre = Timbre()
        val model = model(Store(), timbre, work, owner = null)
        model.recordingReady(audio("draft"))
        advanceUntilIdle()
        assertTrue(model.state.value.canSelectSong)
        assertEquals(OnboardingRangeStatus.READY, model.state.value.rangeAnalysisStatus)
        model.analyzeWhenReady()
        assertTrue(timbre.requests.isEmpty())
    }

    @Test fun automaticTimbreFailureDoesNotCauseAnEndlessRetryLoop() = runTest {
        val work = RangeWork().apply { saved = measurement(); response.complete(verified()) }
        val timbre = Timbre().apply { failure = true }
        val model = model(Store(), timbre, work)
        model.recordingReady(audio("draft"))
        advanceUntilIdle()
        model.analyzeWhenReady()
        advanceUntilIdle()
        model.analyzeWhenReady()
        advanceUntilIdle()
        assertEquals(1, timbre.requests.size)
        model.analyze()
        advanceUntilIdle()
        assertEquals(2, timbre.requests.size)
    }

    @Test fun restoredRecordingAfterRangeCompletionAutomaticallyStartsAnUnsubmittedTimbreAnalysis() = runTest {
        val recording = audio("draft")
        val store = Store().apply { report = verified(); audio = recording; data = data.copy(recordingId = recording.id) }
        val timbre = Timbre().apply {
            recovered = VoiceAnalysisProgress("7", 1, rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.RANGE_IN_PROGRESS)
        }
        val model = model(store, timbre, RangeWork())
        advanceUntilIdle()
        assertTrue(model.state.value.needsInitialTimbreAnalysis)
        model.analyzeWhenReady()
        advanceUntilIdle()
        assertEquals(1, timbre.requests.size)
        assertEquals(7L, timbre.requests.single().range.backendSessionId)
    }
}
