package com.micon.app.data.onboarding

import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.RangeReport

enum class AnalysisComponent(val retryTarget: RetryTarget) {
    ECAPA(RetryTarget.ECAPA), SINGER_BYOL(RetryTarget.BYOL), ARTIST_SEARCH(RetryTarget.ARTIST_SEARCH)
}
enum class RetryTarget { RANGE, ECAPA, BYOL, ARTIST_SEARCH }
enum class ComponentStatus {
    PENDING, QUEUED, RUNNING, COMPLETED, FAILED, INSUFFICIENT_DATA, CANCELED;
    val processing get() = this in setOf(PENDING, QUEUED, RUNNING)
}
enum class VoiceSessionStatus {
    CREATED, RANGE_IN_PROGRESS, TIMBRE_RECORDING, UPLOADING, ANALYZING, PARTIAL_READY,
    READY_TO_SAVE, SAVING, SAVED, INSUFFICIENT_DATA, FAILED, CANCELED, EXPIRED;
    val closed get() = this in setOf(SAVED, CANCELED, EXPIRED)
}

/** This is an HTTP request tag, never serialized. Guest keys must not appear in logs. */
class VoiceSessionAccess(val ownerId: Long?, val guestToken: String? = null) {
    override fun toString() = "VoiceSessionAccess(redacted)"
}

data class SimilarVoiceArtist(val artistId: Long, val name: String, val imageUrl: String?, val similarity: Double)
data class VoiceAnalysisProgress(
    val sessionId: String,
    val version: Long,
    val ecapa: ComponentStatus = ComponentStatus.PENDING,
    val byol: ComponentStatus = ComponentStatus.PENDING,
    val artistSearch: ComponentStatus = ComponentStatus.PENDING,
    val artists: List<SimilarVoiceArtist> = emptyList(),
    val message: String? = null,
    val expiresAtMillis: Long? = null,
    val sessionStatus: VoiceSessionStatus = VoiceSessionStatus.CREATED,
    val saveAllowed: Boolean = false,
    val rangeStatus: String = "PENDING",
    val range: RangeReport? = null,
    val recordingId: String? = null,
    val savedProfileExists: Boolean = false,
    val recoverable: Boolean = true,
    val analysisId: Long? = null,
) {
    // The backend grants eligibility. Completion on the client alone never grants permission to save.
    val canSave get() = saveAllowed && sessionStatus == VoiceSessionStatus.READY_TO_SAVE &&
        rangeStatus == "AVAILABLE" && ecapa == ComponentStatus.COMPLETED && byol == ComponentStatus.COMPLETED
    val analyzing get() = sessionStatus in setOf(VoiceSessionStatus.ANALYZING, VoiceSessionStatus.PARTIAL_READY) &&
        listOf(ecapa, byol, artistSearch).any { it.processing }
    val visibleArtists get() = artists
        .filter { it.artistId > 0 && it.name.isNotBlank() && it.similarity.isFinite() && it.similarity in -1.0..1.0 }
        .sortedByDescending { it.similarity }.distinctBy { it.artistId }.take(5)
    fun status(component: AnalysisComponent) = when (component) {
        AnalysisComponent.ECAPA -> ecapa
        AnalysisComponent.SINGER_BYOL -> byol
        AnalysisComponent.ARTIST_SEARCH -> artistSearch
    }
}

data class ProfileSaveReceipt(val sessionId: String, val version: Long, val profileId: String)
/** draftId/revision are local correlation values, never BE sessionId/resultVersion. */
data class VoiceOnboardingRequest(val draftId: String, val revision: Long, val ownerId: Long?,
    val range: RangeReport, val recording: SavedRecording)

/** App boundary only. This is not a wire DTO or an assumed JSON contract. */
interface VoiceOnboardingGateway {
    val available: Boolean
    suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent? = null,
        onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress
    suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt
    suspend fun recover(ownerId: Long): VoiceAnalysisProgress? = null
    suspend fun latestSaved(ownerId: Long): SavedVoiceOnboardingProfile? = null
    suspend fun cancel(ownerId: Long, result: VoiceAnalysisProgress) {}
}

/** No upload, legacy API fallback, invented job statuses, or fake save while the contract is pending. */
class PendingVoiceOnboardingGateway : VoiceOnboardingGateway {
    override val available = false
    override suspend fun analyze(request: VoiceOnboardingRequest, retry: AnalysisComponent?,
        onProgress: (VoiceAnalysisProgress) -> Unit): VoiceAnalysisProgress = throw VoiceOnboardingNotReady()
    override suspend fun save(request: VoiceOnboardingRequest, result: VoiceAnalysisProgress): ProfileSaveReceipt =
        throw VoiceOnboardingNotReady()
}
class VoiceOnboardingNotReady(message: String = "음색 분석과 프로필 저장 기능을 준비 중이에요. 측정한 음역은 확인할 수 있어요.") : Exception(message)
