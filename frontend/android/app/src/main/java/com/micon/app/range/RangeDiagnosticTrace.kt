package com.micon.app.range

import com.micon.app.core.network.Json
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded, non-blocking observation queue. Disk/JSON work never runs on the audio thread. */
class RangeDiagnosticTrace(file: File, capacity: Int = 4096) : Closeable {
    private val queue = ArrayBlockingQueue<Map<String, Any?>>(capacity)
    private val closing = AtomicBoolean(false)
    val dropped = AtomicLong(0)
    @Volatile var failure: String? = null; private set
    private val output = file.bufferedWriter(bufferSize = 65536)
    private val worker = Thread({
        try {
            val adapter = Json.moshi.adapter(Any::class.java).serializeNulls()
            var flushed = System.nanoTime()
            while (!closing.get() || queue.isNotEmpty()) {
                val entry = queue.poll(100, TimeUnit.MILLISECONDS)
                if (entry != null) { output.write(adapter.toJson(entry)); output.newLine() }
                if (System.nanoTime() - flushed >= 1_000_000_000L) {
                    output.flush(); flushed = System.nanoTime()
                }
            }
            output.write(adapter.toJson(mapOf("type" to "trace_closed", "droppedLogEntries" to dropped.get())))
            output.newLine()
        } catch (e: Exception) { failure = e.javaClass.simpleName }
        finally { runCatching { output.close() }.onFailure { failure = it.javaClass.simpleName } }
    }, "range-diagnostic-writer").apply { isDaemon = true; start() }

    fun record(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (closing.get()) return
        if (failure != null || !queue.offer(fields + mapOf("type" to type, "wallNanos" to System.nanoTime()))) dropped.incrementAndGet()
    }

    override fun close() {
        closing.set(true)
        worker.join(3000)
        check(!worker.isAlive && failure == null) { "Diagnostic log could not be finalized: ${failure ?: "timeout"}" }
    }
}
