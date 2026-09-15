package com.micon.app.data.onboarding

import android.util.AtomicFile
import java.io.File

/** Keeps an upload's identity across process death. Injected in JVM protocol tests. */
interface SubmissionStateStore {
    fun read(file: File): String?
    fun write(file: File, json: String)
    fun delete(file: File)
}

object AtomicSubmissionStateStore : SubmissionStateStore {
    override fun read(file: File): String? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    }
    override fun write(file: File, json: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    override fun delete(file: File) = AtomicFile(file).delete()
}
