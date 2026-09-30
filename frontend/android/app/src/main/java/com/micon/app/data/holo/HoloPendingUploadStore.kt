package com.micon.app.data.holo

import android.content.Context
import androidx.core.util.AtomicFile
import com.micon.app.core.network.Json
import java.io.File

/** Only member recordings can survive an app restart; guest sessions are BE-process local. */
data class HoloPendingUpload(
    val ownerId: Long,
    val fileName: String,
    val resultMeasuredAt: Long,
    val streamId: String,
    val fallbackRequestId: String,
    val sessionId: Long? = null,
    val sampleRate: Int,
    val durationMs: Int,
    val songStartMs: Int,
    val dataVersion: String? = null,
)

class HoloPendingUploadStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "holo")
    private val adapter = Json.moshi.adapter(HoloPendingUpload::class.java)

    private fun target(ownerId: Long) = AtomicFile(File(dir, "pending-member-$ownerId.json"))

    fun read(ownerId: Long): HoloPendingUpload? = runCatching {
        target(ownerId).openRead().bufferedReader().use { input ->
            adapter.fromJson(input.readText())?.takeIf { it.ownerId == ownerId }
        }
    }.getOrNull()

    fun write(value: HoloPendingUpload) {
        dir.mkdirs()
        val atomic = target(value.ownerId)
        val output = atomic.startWrite()
        try {
            output.write(adapter.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    fun clear(ownerId: Long) = target(ownerId).delete()
}
