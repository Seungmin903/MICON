package com.micon.app.data.onboarding

import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavedVoiceReportRepositoryTest {
    private val range = RangeReport(RangeProtocol.VERSION, listOf(RangeNote(60, "passed")),
        60, 72, emptyList(), "completed", "FOUND", "FOUND", "available", backendSessionId = 7)
    private val waiting = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
        byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.RUNNING,
        rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.SAVED)
    private fun profile(progress: VoiceAnalysisProgress = waiting) =
        SavedVoiceOnboardingProfile(ProfileSaveReceipt(progress.sessionId, progress.version, "9"), range, progress)

    private class Store(var profile: SavedVoiceOnboardingProfile?) : VoiceOnboardingPersistence {
        var writes = 0
        var failWrite = false
        override fun savedProfile() = profile
        override fun clearSavedProfile() { profile = null }
        override fun saveProfile(profile: SavedVoiceOnboardingProfile) {
            if (failWrite) error("disk full")
            this.profile = profile; writes++
        }
        override fun draft() = error("unused")
        override fun write(draft: VoiceOnboardingDraft) = error("unused")
        override fun range(): RangeReport? = error("unused")
        override fun recording(id: String?): SavedRecording? = error("unused")
        override fun deleteRecording(id: String?) = error("unused")
        override fun clearDraft() = error("unused")
    }

    private class Gateway : VoiceOnboardingGateway {
        override val available = true
        var saved: SavedVoiceOnboardingProfile? = null
        var updates = emptyList<VoiceAnalysisProgress>()
        var failure: Exception? = null
        var latestCalls = 0
        var beforeLatestResult: () -> Unit = {}
        var lookups = 0
        override suspend fun latestSaved(ownerId: Long): SavedVoiceOnboardingProfile? {
            latestCalls++; failure?.let { throw it }; beforeLatestResult(); return saved
        }
        override suspend fun awaitArtists(ownerId: Long, result: VoiceAnalysisProgress,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress {
            lookups++; updates.forEach(onProgress); return updates.lastOrNull() ?: result
        }
        override suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent?,
            onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress = error("unused")
        override suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt = error("unused")
    }

    @Test fun confirmedMissingProfileClearsPersistentCacheAndDisplayedReport() = runTest {
        val store = Store(profile())
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, Gateway()).refresh(42, shown::add)
        assertNull(store.profile)
        assertNull(shown.last())
    }

    @Test fun invalidatedRefreshCannotRestoreDeletedProfile() = runTest {
        val store = Store(null)
        var valid = true
        val gateway = Gateway().apply { saved = profile(); beforeLatestResult = { valid = false } }
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, gateway, isCurrent = { valid }).refresh(42, shown::add)
        assertTrue(shown.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test fun cachedEmptyReportIsRefreshedWithLateMatchesAtTheSameVersion() = runTest {
        val cached = profile()
        val store = Store(cached)
        val completed = waiting.copy(artistSearch = ComponentStatus.COMPLETED,
            artists = listOf(SimilarVoiceArtist(5, "Singer", null, 0.8)))
        val gateway = Gateway().apply {
            saved = cached.copy(range = range.copy(notes = emptyList()))
            updates = listOf(completed)
        }
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, gateway).refresh(42, shown::add)
        assertSame(cached, shown.first())
        assertEquals(1, gateway.latestCalls)
        assertEquals(1, gateway.lookups)
        assertEquals(completed, shown.last()!!.progress)
        assertEquals(3L, shown.last()!!.receipt.version)
        assertEquals(range.notes, shown.last()!!.range.notes)
        assertEquals(shown.last(), store.profile)
    }

    @Test fun aDifferentSavedSessionReplacesTheOldRangeAndArtistResults() = runTest {
        val store = Store(profile())
        val remote = profile(waiting.copy(sessionId = "8", artistSearch = ComponentStatus.COMPLETED))
            .copy(range = range.copy(lowMidi = 48, backendSessionId = 8))
        val gateway = Gateway().apply { saved = remote }
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, gateway).refresh(42, shown::add)
        assertEquals(remote, shown.last())
        assertEquals(0, gateway.lookups)
    }

    @Test fun offlineRefreshKeepsTheCacheAndDoesNotWriteAnEmptyResult() = runTest {
        val cached = profile()
        val store = Store(cached)
        val gateway = Gateway().apply { failure = java.io.IOException("offline") }
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, gateway).refresh(42, shown::add)
        assertEquals(listOf(cached), shown)
        assertEquals(0, store.writes)
    }

    @Test fun cancellationIsPropagatedAndCannotWriteAProfile() = runTest {
        val store = Store(profile())
        val gateway = Gateway().apply { failure = CancellationException("left report") }
        try {
            SavedVoiceReportRepository(store, gateway).refresh(42) {}
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, store.writes)
    }

    @Test fun wrongSessionAndOlderSnapshotsCannotReplaceTheCurrentMatches() = runTest {
        val store = Store(null)
        val gateway = Gateway().apply {
            saved = profile()
            updates = listOf(waiting.copy(sessionId = "8", artistSearch = ComponentStatus.COMPLETED),
                waiting.copy(version = 2, artistSearch = ComponentStatus.COMPLETED))
        }
        val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
        SavedVoiceReportRepository(store, gateway).refresh(42, shown::add)
        assertEquals(listOf(profile()), shown)
        assertEquals(waiting, store.profile?.progress)
    }

    @Test fun failureAndNoResultAreTerminalAndCacheFailureDoesNotHideServerResults() = runTest {
        for (status in listOf(ComponentStatus.FAILED, ComponentStatus.COMPLETED)) {
            val store = Store(null).apply { failWrite = true }
            val remote = profile(waiting.copy(artistSearch = status))
            val gateway = Gateway().apply { saved = remote }
            val shown = mutableListOf<SavedVoiceOnboardingProfile?>()
            SavedVoiceReportRepository(store, gateway).refresh(42, shown::add)
            assertEquals(listOf(remote), shown)
            assertEquals(0, gateway.lookups)
        }
    }
}
