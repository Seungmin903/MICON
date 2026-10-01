package com.micon.app.data.holo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** 녹음 종료 후 청크 전송은 제한된 시간만 기다린다. 이후에는 보관된 WAV로 접수한다. */
internal sealed interface StreamUploadDrain<out T> {
    data class Ready<T>(val upload: T) : StreamUploadDrain<T>
    data class Fallback(val reason: String) : StreamUploadDrain<Nothing>
}

internal suspend fun <T> drainStreamUpload(job: Deferred<T?>?, timeoutMs: Long): StreamUploadDrain<T> {
    if (job == null) return StreamUploadDrain.Fallback("not_started")
    require(timeoutMs > 0)
    return try {
        val value = withTimeoutOrNull(timeoutMs) { job.await() }
        if (value != null) StreamUploadDrain.Ready(value)
        else StreamUploadDrain.Fallback(if (job.isActive) "timeout" else "unavailable")
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        StreamUploadDrain.Fallback("cancelled")
    } catch (error: Exception) {
        StreamUploadDrain.Fallback(error.javaClass.simpleName)
    } finally {
        if (!job.isCompleted) job.cancel()
    }
}
