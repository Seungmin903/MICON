package com.micon.app.ui.onboarding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.core.network.Json
import com.micon.app.data.onboarding.*
import com.micon.app.range.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceOnboardingStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pending() = PendingRange(RangeSubmission("measurement", startMidi = 60,
        noiseRms = .01, durationSeconds = 1.0, stopReason = "completed",
        lowerBoundaryStatus = "measured", upperBoundaryStatus = "measured",
        attempts = emptyList(), registerEvents = emptyList()))

    @Test fun guestDisposalRemovesDraftAndBlocksLateRangeWrites() {
        val guest = VoiceOnboardingStore(context, null)
        val nextGuest = VoiceOnboardingStore(context, null)
        try {
            assertNotEquals(guest.draft().draftId, nextGuest.draft().draftId)
            val wav = guest.rangeRepository.audio("measurement")
            wav.writeBytes(ByteArray(100))
            guest.rangeRepository.savePending(pending())
            assertNotNull(guest.rangeRepository.pending())
            guest.disposeGuest()
            assertFalse(wav.exists())
            guest.rangeRepository.savePending(pending())
            assertNull(guest.rangeRepository.pending())
            assertNotNull(nextGuest.draft())
        } finally { guest.disposeGuest(); nextGuest.disposeGuest() }
    }

    @Test fun membersRecoverOnlyTheirOwnDraftAndGuestCannotSaveProfile() {
        val owner = System.currentTimeMillis()
        val member = VoiceOnboardingStore(context, owner)
        val other = VoiceOnboardingStore(context, owner + 1)
        val guest = VoiceOnboardingStore(context, null)
        try {
            val draft = member.draft().copy(revision = 7, expiresAtMillis = 123456)
            member.write(draft)
            assertEquals(draft, VoiceOnboardingStore(context, owner).draft())
            assertNotEquals(draft.draftId, other.draft().draftId)
            val report = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
                "completed", "measured", "measured", "available")
            try {
                guest.saveProfile(SavedVoiceOnboardingProfile(ProfileSaveReceipt("guest", 1, "id"),
                    report, VoiceAnalysisProgress("guest", 1)))
                fail("Guest profile should not be persisted")
            } catch (_: IllegalStateException) { }
            assertNull(guest.savedProfile())
        } finally { member.clearDraft(); other.clearDraft(); guest.disposeGuest() }
    }

    @Test fun freshStartSurvivesStoreRecreationAndPreservesSavedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = 9_000_000_000L + System.nanoTime().rem(1_000_000_000L)
        val directory = File(context.filesDir, "voice-onboarding/member-$owner")
        try {
            val store = VoiceOnboardingStore(context, owner)
            val report = RangeReport(RangeProtocol.VERSION, emptyList(), 48, 72, emptyList(),
                "completed", "measured", "measured", "available", verification = "device_measured", backendSessionId = 7)
            store.write(VoiceOnboardingDraft("previous", expiresAtMillis = Long.MAX_VALUE, serverSessionId = 7))
            File(directory, "range/last-session-report.json").writeText(Json.moshi.adapter(RangeReport::class.java).toJson(report))
            File(directory, "range-link.json").writeText("{}")
            val profile = SavedVoiceOnboardingProfile(ProfileSaveReceipt("7", 1, "saved"), report, VoiceAnalysisProgress("7", 1))
            store.saveProfile(profile)
            assertTrue(store.hasResumableDraft(100))

            val fresh = store.startNewDraft()
            val recreated = VoiceOnboardingStore(context, owner)
            assertNotEquals("previous", fresh.draftId)
            assertEquals(fresh, recreated.draft())
            assertNull(recreated.draft().serverSessionId)
            assertNull(recreated.range())
            assertFalse(recreated.hasResumableDraft(100))
            assertFalse(File(directory, "range-link.json").exists())
            assertEquals(profile, recreated.savedProfile())
        } finally { directory.deleteRecursively() }
    }

    @Test fun olderDraftWithoutSessionFieldStillLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = 9_000_000_000L + System.nanoTime().rem(1_000_000_000L)
        val directory = File(context.filesDir, "voice-onboarding/member-$owner")
        try {
            val store = VoiceOnboardingStore(context, owner)
            File(directory, "draft.json").writeText("""{"draftId":"legacy","revision":3,"recordingId":null,"expiresAtMillis":null}""")
            assertEquals("legacy", store.draft().draftId)
            assertEquals(3L, store.draft().revision)
            assertNull(store.draft().serverSessionId)
        } finally { directory.deleteRecursively() }
    }
}
