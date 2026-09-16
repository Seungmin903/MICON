package com.micon.app.ui.onboarding

import com.micon.app.data.onboarding.*
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOnboardingEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Store : VoiceOnboardingPersistence {
        var data = VoiceOnboardingDraft("previous", serverSessionId = 7)
        var report: RangeReport? = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
            "completed", "measured", "measured", "available", backendSessionId = 7)
        var pending = false
        var clears = 0
        var failWrite = false
        var savedProfile = SavedVoiceOnboardingProfile(ProfileSaveReceipt("1", 1, "saved"), report!!,
            VoiceAnalysisProgress("1", 1))
        override fun draft() = data
        override fun write(draft: VoiceOnboardingDraft) { if (failWrite) error("disk full"); data = draft }
        override fun range() = report
        override fun recording(id: String?): SavedRecording? = null
        override fun deleteRecording(id: String?) = Unit
        override fun saveProfile(profile: SavedVoiceOnboardingProfile) { savedProfile = profile }
        override fun hasPendingRange() = pending
        override fun clearDraft() { clears++; report = null; pending = false; data = VoiceOnboardingDraft("empty") }
    }

    private fun model(store: Store?, current: () -> Boolean = { true }, now: () -> Long = { 100 }) =
        VoiceOnboardingEntryViewModel(store, current, now, dispatcher)

    @Test fun previousMeasurementAsksBeforeChangingAnything() = runTest {
        val store = Store()
        val entry = model(store)
        entry.open(); advanceUntilIdle()
        assertTrue(entry.state.value.choosing)
        assertFalse(entry.state.value.navigate)
        assertEquals("previous", store.data.draftId)
        assertEquals(0, store.clears)
    }

    @Test fun resumeKeepsTheSameDraftAndServerSession() = runTest {
        val store = Store()
        val entry = model(store)
        entry.open(); advanceUntilIdle()
        entry.resume(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
        assertFalse(entry.state.value.choosing)
        assertEquals("previous", store.data.draftId)
        assertEquals(7L, store.data.serverSessionId)
        assertEquals(0, store.clears)
    }

    @Test fun startNewResetsPendingDataButPreservesTheSavedProfile() = runTest {
        val store = Store().apply { pending = true }
        val saved = store.savedProfile
        val entry = model(store)
        entry.open(); advanceUntilIdle()
        entry.startNew(); entry.startNew(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
        assertEquals(1, store.clears)
        assertNotEquals("previous", store.data.draftId)
        assertNull(store.data.serverSessionId)
        assertNull(store.report)
        assertFalse(store.pending)
        assertSame(saved, store.savedProfile)
    }

    @Test fun dismissLeavesMeasurementAvailableForTheNextEntry() = runTest {
        val store = Store()
        val entry = model(store)
        entry.open(); advanceUntilIdle(); entry.dismiss()
        assertEquals(VoiceOnboardingEntryState(), entry.state.value)
        entry.open(); advanceUntilIdle()
        assertTrue(entry.state.value.choosing)
        assertEquals(0, store.clears)
    }

    @Test fun pendingRangeAloneCanBeResumed() = runTest {
        val entry = model(Store().apply { report = null; pending = true })
        entry.open(); advanceUntilIdle()
        assertTrue(entry.state.value.choosing)
    }

    @Test fun noProgressStartsDirectlyAndDoubleTapDoesNotResetTwice() = runTest {
        val store = Store().apply { report = null }
        val entry = model(store)
        entry.open(); entry.open(); advanceUntilIdle(); entry.open()
        assertTrue(entry.state.value.navigate)
        assertEquals(1, store.clears)
        assertNull(store.data.serverSessionId)
    }

    @Test fun expiredDraftStartsFreshWithoutOfferingUnusableResume() = runTest {
        val store = Store().apply { data = data.copy(expiresAtMillis = 99) }
        val entry = model(store)
        entry.open(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
        assertFalse(entry.state.value.choosing)
        assertNull(store.data.expiresAtMillis)
    }

    @Test fun expiryWhileDialogIsOpenDoesNotResumeExpiredSession() = runTest {
        var time = 100L
        val store = Store().apply { data = data.copy(expiresAtMillis = 101) }
        val entry = model(store, now = { time })
        entry.open(); advanceUntilIdle()
        time = 102
        entry.resume(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
        assertEquals(1, store.clears)
    }

    @Test fun accountSwitchRejectsQueuedResetAndNavigation() = runTest {
        var current = true
        val store = Store()
        val entry = model(store, current = { current })
        entry.open(); advanceUntilIdle()
        entry.startNew(); current = false; advanceUntilIdle()
        assertFalse(entry.state.value.navigate)
        assertEquals(0, store.clears)
    }

    @Test fun guestStartsWithoutReadingOrClearingMemberData() = runTest {
        val entry = model(null)
        entry.open(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
        assertFalse(entry.state.value.choosing)
    }

    @Test fun failedResetDoesNotNavigateAndCanBeRetried() = runTest {
        val store = Store().apply { failWrite = true }
        val entry = model(store)
        entry.open(); advanceUntilIdle()
        entry.startNew(); advanceUntilIdle()
        assertFalse(entry.state.value.navigate)
        assertNotNull(entry.state.value.error)
        store.failWrite = false
        entry.startNew(); advanceUntilIdle()
        assertTrue(entry.state.value.navigate)
    }
}
