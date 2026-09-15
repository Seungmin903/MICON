package com.micon.app.data.onboarding

import java.io.File

class TestSubmissionStateStore : SubmissionStateStore {
    private val data = mutableMapOf<String, String>()
    override fun read(file: File) = data[file.absolutePath]
    override fun write(file: File, json: String) { data[file.absolutePath] = json }
    override fun delete(file: File) { data.remove(file.absolutePath) }
}
