package com.micon.app.data.onboarding

import kotlinx.coroutines.CancellationException

/** Show the cached report immediately, then keep it in sync with late artist results. */
class SavedVoiceReportRepository(
    private val persistence: VoiceOnboardingPersistence,
    private val gateway: VoiceOnboardingGateway,
) {
    suspend fun refresh(ownerId: Long, onProfile: (SavedVoiceOnboardingProfile) -> Unit) {
        var current = persistence.savedProfile()
        current?.let(onProfile)
        try {
            val remote = gateway.latestSaved(ownerId) ?: return
            val cached = current
            // Server range DTOs omit some of the detailed measurements stored on this device.
            val refreshed = if (cached?.receipt?.sessionId == remote.receipt.sessionId) {
                remote.copy(range = cached.range.copy(backendSessionId = remote.range.backendSessionId))
            } else remote
            current = refreshed
            onProfile(refreshed)
            runCatching { persistence.saveProfile(refreshed) }

            fun accept(progress: VoiceAnalysisProgress) {
                val previous = checkNotNull(current)
                if (progress.sessionId != previous.receipt.sessionId || progress.version < previous.progress.version) return
                val updated = previous.copy(
                    receipt = previous.receipt.copy(version = progress.version), progress = progress)
                if (updated == previous) return
                current = updated
                onProfile(updated)
                runCatching { persistence.saveProfile(updated) }
            }
            if (refreshed.progress.awaitingArtistResult) {
                accept(gateway.awaitArtists(ownerId, refreshed.progress, ::accept))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A connection failure leaves the measured report and previous matches visible.
        }
    }
}
