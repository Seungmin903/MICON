package com.micon.app.range

import android.content.Context
import com.micon.app.BuildConfig
import com.micon.app.data.engine.EngineClient
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
class RangeRepository(context: Context, private val modelApi: RangePreviewClient.Api? = null) {
    private val appContext = context.applicationContext
    private val directory = File(context.filesDir, "guided-range-preview").apply { mkdirs() }
    private val preview by lazy { modelApi ?: RangePreviewClient(BuildConfig.RANGE_PREVIEW_BASE_URL).api }
    private val pendingFile = File(directory, "pending.json")
    private val reportFile = File(directory, "report.json")
    private val lastSessionFile = File(directory, "last-session-report.json")
    private val pendingAdapter = EngineClient.moshi.adapter(PendingRange::class.java)
    private val reportAdapter = EngineClient.moshi.adapter(RangeReport::class.java)
    private val submissionAdapter = EngineClient.moshi.adapter(RangeSubmission::class.java)
    private var finalized = false
    fun audio(id: String) = File(directory, "$id.wav")
    private fun readAtomic(file: File) = android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    fun pending(): PendingRange? = runCatching { pendingAdapter.fromJson(readAtomic(pendingFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.submission.protocolVersion) }
    fun localReport(): RangeReport? = runCatching { reportAdapter.fromJson(readAtomic(reportFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.protocolVersion) &&
            it.verification == "model_verified" }
    fun lastSessionReport(): RangeReport? = runCatching { reportAdapter.fromJson(readAtomic(lastSessionFile)) }
        .getOrNull()?.takeIf { RangeProtocol.supports(it.protocolVersion) &&
            it.verification == "model_verified" } ?: localReport()

    fun discardPending() {
        pending()?.let { if (!RangeDiagnostics.retainsAudio(appContext, it.submission.sessionId)) audio(it.submission.sessionId).delete() }
        android.util.AtomicFile(pendingFile).delete()
    }

    @Synchronized fun savePending(pending: PendingRange, checkpoint: Boolean = false) {
        if (checkpoint && finalized) return
        if (!checkpoint) finalized = true
        atomicWrite(pendingFile, pendingAdapter.toJson(pending))
    }

    private fun atomicWrite(file: File, text: String) {
        // AtomicFile is available at the minimum Android API and preserves the previous successful write.
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    private fun saveReport(report: RangeReport) {
        atomicWrite(reportFile, reportAdapter.toJson(report))
    }

    suspend fun submit(onState: (String) -> Unit): RangeReport = withContext(Dispatchers.IO) {
        val saved = pending() ?: error("저장된 측정이 없습니다.")
        val wav = audio(saved.submission.sessionId)
        repairWav(wav, saved.sampleRate)
        val available = (wav.length() - 44).toDouble() / (saved.sampleRate * 2)
        val submission = saved.submission.recoverAudio(available)
        savePending(saved.copy(submission = submission))
        onState("분석 중…")
        val report = preview.analyze(
            MultipartBody.Part.createFormData("audio", wav.name, wav.asRequestBody("audio/wav".toMediaType())),
            submissionAdapter.toJson(submission).toRequestBody("text/plain; charset=utf-8".toMediaType()))
        check(report.protocolVersion == submission.protocolVersion && report.verification == "model_verified") { "모델 검증 결과를 확인하지 못했습니다." }
        atomicWrite(lastSessionFile, reportAdapter.toJson(report))
        if (report.availability == "available") saveReport(report)
        if (!saved.submission.keepOriginal && !RangeDiagnostics.retainsAudio(appContext, saved.submission.sessionId)) audio(saved.submission.sessionId).delete()
        android.util.AtomicFile(pendingFile).delete()
        return@withContext report
    }

    /** AudioRecord may be interrupted before stop() finalizes the header. PCM remains recoverable. */
    private fun repairWav(file: File, rate: Int) {
        check(file.isFile && file.length() > 44) { "녹음이 없습니다. 다시 측정해 주세요." }
        val bytes = ((file.length() - 44) / 2 * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        h.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(bytes)
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(h.array()) }
    }
}
