package com.micon.app.range

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.micon.app.BuildConfig
import com.micon.app.core.network.Json
import java.io.File

/** Explicit debug token permits precisely one session, without changing server audio retention. */
class RangeDiagnostics private constructor(private val directory: File, val trace: RangeDiagnosticTrace) {
    fun snapshot(submission: RangeSubmission, sampleRate: Int) {
        write(File(directory, "submission.json"), Json.moshi.adapter(RangeSubmission::class.java).toJson(submission))
        trace.record("checkpoint", mapOf("audioTime" to submission.durationSeconds, "sampleRate" to sampleRate,
            "attemptCount" to submission.attempts.size, "stopReason" to submission.stopReason))
    }

    fun finish(submission: RangeSubmission, sampleRate: Int) {
        try { snapshot(submission, sampleRate) }
        finally { trace.close() }
    }

    companion object {
        private const val PREFERENCES = "guided-range-diagnostic-once"
        private fun folder(context: Context, id: String) = File(context.filesDir, "range-diagnostics/$id")
        fun armed(context: Context, token: String = BuildConfig.GUIDED_RANGE_DIAGNOSTIC_TOKEN): Boolean =
            BuildConfig.DEBUG && token.isNotBlank() && !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("used_$token", false)

        @Synchronized fun claim(context: Context, id: String, audio: File,
                                token: String = BuildConfig.GUIDED_RANGE_DIAGNOSTIC_TOKEN): RangeDiagnostics? {
            if (!armed(context, token)) return null
            require(id.matches(Regex("[A-Za-z0-9_-]+")))
            val dir = folder(context, id)
            check(dir.mkdirs()) { "Diagnostic directory could not be created" }
            val manifest = mapOf("schema" to "range-diagnostic-v1", "sessionId" to id, "token" to token,
                "protocolVersion" to RangeProtocol.VERSION, "audioPath" to audio.absolutePath,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "androidSdk" to Build.VERSION.SDK_INT,
                "createdAtMillis" to System.currentTimeMillis(), "appVersion" to BuildConfig.VERSION_NAME,
                "localRetentionOnly" to true, "scope" to "one_session")
            // The marker also protects the normal WAV through uploads, retries, and process recreation.
            write(File(dir, "manifest.json"), Json.moshi.adapter(Any::class.java).toJson(manifest))
            val trace = RangeDiagnosticTrace(File(dir, "trace.ndjson"))
            if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("used_$token", true).commit()) {
                trace.close()
                error("Diagnostic one-session setting could not be saved")
            }
            trace.record("session_started", manifest)
            return RangeDiagnostics(dir, trace)
        }

        fun retainsAudio(context: Context, id: String): Boolean =
            id.matches(Regex("[A-Za-z0-9_-]+")) && File(folder(context, id), "manifest.json").isFile

        private fun write(file: File, text: String) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        }
    }
}
