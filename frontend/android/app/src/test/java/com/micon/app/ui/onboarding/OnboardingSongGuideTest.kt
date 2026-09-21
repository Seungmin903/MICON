package com.micon.app.ui.onboarding

import com.micon.app.core.network.Json
import com.micon.app.data.onboarding.*
import com.micon.app.range.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSongGuideTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private val range = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
        "completed", "FOUND", "FOUND", "available", verification = "device_measured")
    private class Store : VoiceOnboardingPersistence {
        var data = VoiceOnboardingDraft("draft")
        var report: RangeReport? = null
        var failWrite = false
        var writes = 0
        override fun draft() = data
        override fun write(draft: VoiceOnboardingDraft) {
            if (failWrite) error("disk full")
            writes++; data = draft
        }
        override fun range() = report
        override fun recording(id: String?) = null
        override fun deleteRecording(id: String?) {}
        override fun saveProfile(profile: SavedVoiceOnboardingProfile) = error("unused")
        override fun clearDraft() { report = null }
    }

    @Test fun priorDraftsDefaultToAnUnseenGuideAndNewFieldRoundTrips() {
        val adapter = Json.moshi.adapter(VoiceOnboardingDraft::class.java)
        val old = checkNotNull(adapter.fromJson("""{"draftId":"old","revision":3}"""))
        assertFalse(old.songGuideCompleted)
        assertFalse(old.analysisGuideCompleted)
        assertFalse(old.rangeGuideCompleted)
        val draft = VoiceOnboardingDraft("draft", songGuideCompleted = true,
            analysisGuideCompleted = true, rangeGuideCompleted = true)
        assertEquals(draft, adapter.fromJson(adapter.toJson(draft)))
    }

    @Test fun analysisAndRangeGuidesPersistOnceBeforeMeasurement() {
        val store = Store()
        val model = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertEquals("analysis-guide", onboardingInitialDestination(model.state.value))
        assertTrue(model.completeAnalysisGuide())
        assertEquals("range-guide", onboardingInitialDestination(model.state.value))
        assertTrue(model.completeAnalysisGuide())
        assertEquals(1, store.writes)
        assertTrue(model.completeRangeGuide())
        assertEquals("measure", onboardingInitialDestination(model.state.value))
        assertEquals(2, store.writes)
        val restored = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertEquals("measure", onboardingInitialDestination(restored.state.value))
    }

    @Test fun guideCopyNormalizesHonorificAndFallsBackWithoutAName() {
        assertEquals("유림님", onboardingHonorificName(" 유림 "))
        assertEquals("유림님", onboardingHonorificName("유림님"))
        assertEquals("두 단계에 따라 목소리를 분석할거에요", analysisGuideCopy(null))
        assertEquals("지금부터는 유림님이 낼 수 있는 소리의 범위를 측정해요", rangeGuideCopy("유림님"))
    }

    @Test fun confirmingGuideIsPersistedOnceAndRestoredWithoutRepeating() {
        val store = Store().apply { report = range }
        val model = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertEquals("song-guide", onboardingInitialDestination(model.state.value))
        assertTrue(model.completeSongGuide())
        assertTrue(model.completeSongGuide())
        assertEquals(1, store.writes)
        val restored = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertEquals("pick", onboardingInitialDestination(restored.state.value))
        model.newRecording()
        assertTrue(model.state.value.songGuideCompleted)
        model.restart()
        assertFalse(model.state.value.songGuideCompleted)
        assertEquals("analysis-guide", onboardingInitialDestination(model.state.value))
    }

    @Test fun storageFailureAndMissingMeasurementCannotSkipTheGuide() {
        val store = Store()
        val model = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertFalse(model.completeSongGuide())
        store.report = range
        assertTrue(model.rangeReady())
        store.failWrite = true
        assertFalse(model.completeSongGuide())
        assertFalse(model.state.value.songGuideCompleted)
        assertNotNull(model.state.value.songGuideError)
        store.failWrite = false
        assertTrue(model.completeSongGuide())
        assertNull(model.state.value.songGuideError)
    }

    @Test fun verifiedRangeFromTheResultPageReopensTheSongGuideForTheNewMeasurement() {
        val store = Store().apply { data = data.copy(songGuideCompleted = true) }
        val model = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway())
        assertFalse(model.state.value.canSelectSong)
        assertFalse(model.rangeReady())
        store.report = range
        assertTrue(model.rangeReady())
        assertTrue(model.state.value.canSelectSong)
        assertFalse(model.state.value.songGuideCompleted)
        assertFalse(store.data.songGuideCompleted)
        assertEquals("song-guide", onboardingInitialDestination(model.state.value))
    }

    @Test fun expiredAndSignedOutOwnersCannotConfirmTheGuide() {
        val store = Store().apply { report = range }
        val signedOut = VoiceOnboardingViewModel(1, store, PendingVoiceOnboardingGateway(), isCurrentOwner = { false })
        assertFalse(signedOut.completeSongGuide())
        store.data = store.data.copy(expiresAtMillis = 1)
        val expired = VoiceOnboardingViewModel(null, store, PendingVoiceOnboardingGateway(), now = { 2 })
        assertFalse(expired.completeSongGuide())
        assertEquals("expired", onboardingInitialDestination(expired.state.value))
        assertEquals(0, store.writes)
    }
}
