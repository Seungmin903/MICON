package com.micon.app.data.onboarding

import android.content.Context
import android.util.AtomicFile
import com.micon.app.core.network.Json
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.data.singanalysis.SingAnalysisRecordingStore
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeRepository
import java.io.File
import java.util.UUID

data class VoiceOnboardingDraft(val draftId: String, val revision: Long = 0,
    val recordingId: String? = null, val expiresAtMillis: Long? = null,
    val serverSessionId: Long? = null, val songGuideCompleted: Boolean = false)
data class SavedVoiceOnboardingProfile(val receipt: ProfileSaveReceipt, val range: RangeReport,
    val progress: VoiceAnalysisProgress)

interface VoiceOnboardingPersistence {
    fun draft(): VoiceOnboardingDraft
    fun write(draft: VoiceOnboardingDraft)
    fun range(): RangeReport?
    fun recording(id: String?): SavedRecording?
    fun deleteRecording(id: String?)
    fun saveProfile(profile: SavedVoiceOnboardingProfile)
    fun savedProfile(): SavedVoiceOnboardingProfile? = null
    fun clearSavedProfile() {}
    fun clearDraft()
    fun hasPendingRange(): Boolean = false
}

fun VoiceOnboardingPersistence.hasResumableDraft(now: Long): Boolean {
    val draft = draft()
    if (draft.expiresAtMillis?.let { it <= now } == true) return false
    return range() != null || hasPendingRange() || recording(draft.recordingId) != null
}

fun VoiceOnboardingPersistence.startNewDraft(): VoiceOnboardingDraft {
    clearDraft()
    return VoiceOnboardingDraft(UUID.randomUUID().toString()).also(::write)
}

/** One member draft per account; guests get an isolated directory, never a member database/profile. */
class VoiceOnboardingStore(context: Context, val ownerId: Long?,
    backendApi: com.micon.app.data.backend.BackendApi? = null) : VoiceOnboardingPersistence {
    private val directory = if (ownerId != null) File(context.filesDir, "voice-onboarding/member-$ownerId")
        else File(context.cacheDir, "voice-onboarding-guests/${UUID.randomUUID()}")
    private val draftFile = File(directory, "draft.json")
    private val profileFile = File(directory, "saved-profile.json")
    val rangeRepository = RangeRepository(context, storageDirectory = File(directory, "range"),
        deviceOnly = backendApi == null || ownerId == null,
        serverGateway = if (backendApi != null && ownerId != null)
            BackendRangeAnalysisGateway(backendApi, ownerId, File(directory, "range-link.json")) else null)
    val rangeAnalysis: OnboardingRangeAnalysis = RepositoryOnboardingRangeAnalysis(rangeRepository)
    val recordings = SingAnalysisRecordingStore(context, storageDirectory = File(directory, "recordings"))
    private val draftAdapter = Json.moshi.adapter(VoiceOnboardingDraft::class.java)
    private val profileAdapter = Json.moshi.adapter(SavedVoiceOnboardingProfile::class.java)

    init { require(ownerId == null || ownerId > 0); directory.mkdirs() }

    override fun draft(): VoiceOnboardingDraft = runCatching {
        AtomicFile(draftFile).openRead().bufferedReader().use { draftAdapter.fromJson(it.readText()) }
    }.getOrNull() ?: VoiceOnboardingDraft(UUID.randomUUID().toString()).also(::write)

    override fun write(draft: VoiceOnboardingDraft) = atomicWrite(draftFile, draftAdapter.toJson(draft))
    override fun range(): RangeReport? = rangeRepository.lastSessionReport()?.takeIf { it.availability == "available" }
    override fun hasPendingRange(): Boolean = rangeRepository.pending() != null
    override fun recording(id: String?): SavedRecording? = recordings.list().find { it.id == id }
    override fun deleteRecording(id: String?) { if (id != null) recordings.delete(id) }

    override fun saveProfile(profile: SavedVoiceOnboardingProfile) {
        check(ownerId != null) { "비회원 결과는 저장할 수 없습니다." }
        atomicWrite(profileFile, profileAdapter.toJson(profile))
    }

    override fun savedProfile(): SavedVoiceOnboardingProfile? = if (ownerId == null) null else runCatching {
        AtomicFile(profileFile).openRead().bufferedReader().use { profileAdapter.fromJson(it.readText()) }
    }.getOrNull()

    override fun clearDraft() {
        rangeRepository.clear()
        AtomicFile(File(directory, "range-link.json")).delete()
        recordings.list().forEach { recordings.delete(it.id) }
        recordings.deleteOrphans()
        AtomicFile(draftFile).delete()
    }

    override fun clearSavedProfile() { AtomicFile(profileFile).delete() }

    fun clearVoiceData() {
        clearSavedProfile()
        clearDraft()
    }

    fun disposeGuest() {
        if (ownerId == null) {
            rangeRepository.closeTemporary()
            recordings.closeTemporary()
            directory.deleteRecursively()
        }
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    companion object {
        fun clearInterruptedGuests(context: Context) {
            File(context.cacheDir, "voice-onboarding-guests").deleteRecursively()
        }
    }
}
