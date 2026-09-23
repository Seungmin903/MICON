package com.micon.app.range

import android.content.Context
import com.micon.app.BuildConfig
import com.micon.app.core.network.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PendingRange(val submission: RangeSubmission, val analysisId: String? = null, val sampleRate: Int = 16000)

/** Local model-verified reports and pending audio survive failed requests and app restarts. */
class RangeRepository(context: Context, private val modelApi: RangePreviewClient.Api? = null,
    storageDirectory: File? = null, val deviceOnly: Boolean = false,
    private val serverGateway: RangeAnalysisGateway? = null,
) {
    private val appContext = context.applicationContext
    private val directory = (storageDirectory ?: File(context.filesDir, "guided-range-preview")).apply { mkdirs() }
    private val preview by lazy { modelApi ?: RangePreviewClient(BuildConfig.RANGE_PREVIEW_BASE_URL).api }
    private val pendingFile = File(directory, "pending.json")
    private val freeCheckpointFile = File(directory, "free-range-checkpoint.json")
    private val freeCheckpointAdapter = Json.moshi.adapter(FreeRangeCheckpoint::class.java)
    private val reportFile = File(directory, "report.json")
    private val lastSessionFile = File(directory, "last-session-report.json")
    private val completedTargetsFile = File(directory, "completed-targets.json")
    private val completedTargetsAdapter = Json.moshi.adapter(CompletedRangeTargets::class.java)
    private val pendingAdapter = Json.moshi.adapter(PendingRange::class.java)
    private val reportAdapter = Json.moshi.adapter(RangeReport::class.java)
    private val submissionAdapter = Json.moshi.adapter(RangeSubmission::class.java)
    private var finalized = false
    @Volatile private var closed = false
    fun audio(id: String) = File(directory, "$id.wav")
    fun freeCheckpoint(): FreeRangeCheckpoint? = runCatching {
        freeCheckpointAdapter.fromJson(readAtomic(freeCheckpointFile))
    }.getOrNull()?.takeIf { audio(it.id).isFile }
    @Synchronized fun saveFreeCheckpoint(checkpoint: FreeRangeCheckpoint) {
        atomicWrite(freeCheckpointFile, freeCheckpointAdapter.toJson(checkpoint))
    }
    @Synchronized fun clearFreeCheckpoint() { android.util.AtomicFile(freeCheckpointFile).delete() }
    private fun readAtomic(file: File) = android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    fun pending(): PendingRange? = runCatching { pendingAdapter.fromJson(readAtomic(pendingFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.submission.protocolVersion) }
    fun localReport(): RangeReport? = runCatching { reportAdapter.fromJson(readAtomic(reportFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.protocolVersion) &&
            it.verification == if (deviceOnly) "device_measured" else "model_verified" }
    fun lastSessionReport(): RangeReport? = runCatching { reportAdapter.fromJson(readAtomic(lastSessionFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.protocolVersion) &&
            it.verification == if (deviceOnly) "device_measured" else "model_verified" } ?: localReport()

    fun discardPending() {
        clearFreeCheckpoint()
        pending()?.let { if (!RangeDiagnostics.retainsAudio(appContext, it.submission.sessionId)) audio(it.submission.sessionId).delete() }
        android.util.AtomicFile(pendingFile).delete()
    }

    /** Start a fresh free-range attempt without erasing the last verified report or voice profile. */
    @Synchronized fun discardUnfinishedFreeMeasurement() {
        val checkpointId = freeCheckpoint()?.id
        val pendingId = pending()?.submission?.sessionId
        discardPending()
        if (checkpointId != null && checkpointId != pendingId && !RangeDiagnostics.retainsAudio(appContext, checkpointId)) {
            audio(checkpointId).delete()
        }
        finalized = false
    }

    @Synchronized fun savePending(pending: PendingRange, checkpoint: Boolean = false) {
        if (closed) return
        if (checkpoint && finalized) return
        if (!checkpoint) finalized = true
        atomicWrite(pendingFile, pendingAdapter.toJson(pending))
        if (!checkpoint) clearFreeCheckpoint()
    }

    @Synchronized private fun atomicWrite(file: File, text: String) {
        if (closed) return
        // AtomicFile is available at the minimum Android API and preserves the previous successful write.
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun completedTargets(report: RangeReport): Set<Int>? = targetHistory()
        ?.takeIf { it.analysisId == report.analysisId && it.protocolVersion == report.protocolVersion }?.targets

    private fun targetHistory(): CompletedRangeTargets? = runCatching {
        completedTargetsAdapter.fromJson(readAtomic(completedTargetsFile))
    }.getOrNull()

    private fun saveReport(report: RangeReport, submission: RangeSubmission) {
        val previous = targetHistory()?.takeIf { it.analysisId == submission.parentAnalysisId &&
            it.protocolVersion == submission.protocolVersion }?.targets ?: localReport()
            ?.takeIf { submission.parentAnalysisId != null && it.analysisId == submission.parentAnalysisId && it.protocolVersion == submission.protocolVersion }
            ?.completedNoteFallback().orEmpty()
        val targets = CompletedRangeTargets(report.analysisId ?: submission.sessionId, report.protocolVersion,
            previous + submission.completedTargets())
        atomicWrite(completedTargetsFile, completedTargetsAdapter.toJson(targets))
        atomicWrite(reportFile, reportAdapter.toJson(report))
    }

    suspend fun submit(onState: (String) -> Unit): RangeReport = withContext(Dispatchers.IO) {
        val saved = pending() ?: error("저장된 측정이 없습니다.")
        val wav = audio(saved.submission.sessionId)
        // Validate before recovery can trim invalid metadata into an apparently usable submission.
        val input = RangeInputQuality().inspect(saved.submission, wav, saved.sampleRate, finalized = true)
        check(input.ready) { input.message }
        repairWav(wav, saved.sampleRate)
        val available = (wav.length() - 44).toDouble() / (saved.sampleRate * 2)
        val submission = saved.submission.recoverAudio(available)
        synchronized(this@RangeRepository) {
            checkCurrentMeasurement(saved)
            savePending(saved.copy(submission = submission))
        }
        if (deviceOnly) {
            onState("측정 결과를 정리하고 있어요")
            val report = submission.deviceReport()
            synchronized(this@RangeRepository) {
                checkCurrentMeasurement(saved)
                atomicWrite(lastSessionFile, reportAdapter.toJson(report))
                if (report.availability == "available") saveReport(report, submission)
                wav.delete()
                android.util.AtomicFile(pendingFile).delete()
            }
            return@withContext report
        }
        onState("분석 중…")
        val report = serverGateway?.analyze(wav, submission) ?: preview.analyze(
            MultipartBody.Part.createFormData("audio", wav.name, wav.asRequestBody("audio/wav".toMediaType())),
            submissionAdapter.toJson(submission).toRequestBody("text/plain; charset=utf-8".toMediaType()))
        check(report.protocolVersion == submission.protocolVersion && report.verification == "model_verified") { "모델 검증 결과를 확인하지 못했습니다." }
        synchronized(this@RangeRepository) {
            checkCurrentMeasurement(saved)
            atomicWrite(lastSessionFile, reportAdapter.toJson(report))
            if (report.availability == "available") saveReport(report, submission)
            if (!saved.submission.keepOriginal && !RangeDiagnostics.retainsAudio(appContext, saved.submission.sessionId)) audio(saved.submission.sessionId).delete()
            android.util.AtomicFile(pendingFile).delete()
        }
        return@withContext report
    }

    private fun checkCurrentMeasurement(saved: PendingRange) {
        check(!closed && pending()?.submission?.sessionId == saved.submission.sessionId) {
            "새 측정이 시작되어 이전 분석 결과를 반영하지 않았어요."
        }
    }

    @Synchronized fun clear() {
        discardPending()
        android.util.AtomicFile(reportFile).delete()
        android.util.AtomicFile(lastSessionFile).delete()
        android.util.AtomicFile(completedTargetsFile).delete()
        finalized = false
    }

    @Synchronized fun closeTemporary() { closed = true; clear() }

    /** AudioRecord may be interrupted before stop() finalizes the header. PCM remains recoverable. */
    fun repairWav(file: File, rate: Int) {
        require(rate in setOf(16000, 44100, 48000)) { "지원하지 않는 녹음 형식입니다." }
        check(file.isFile && file.length() > 44) { "녹음이 없습니다. 다시 측정해 주세요." }
        val bytes = ((file.length() - 44) / 2 * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        h.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(bytes)
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(h.array()) }
    }
}
