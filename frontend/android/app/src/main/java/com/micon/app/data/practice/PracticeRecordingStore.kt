package com.micon.app.data.practice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Device-only microphone takes. Both IDs are server-issued numbers, never path fragments from input text. */
class PracticeRecordingStore(private val root: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, DIRECTORY))

    fun find(ownerId: Long, practiceId: Long): File? = recordingFile(ownerId, practiceId)
        .takeIf(::isPlayableWav)

    /** Copy first, then publish the complete WAV. The practice screen keeps its temporary take for immediate replay. */
    @Synchronized
    fun save(ownerId: Long, practiceId: Long, source: File): File {
        require(isPlayableWav(source)) { "연습 녹음 파일이 올바르지 않습니다." }
        val destination = recordingFile(ownerId, practiceId)
        if (isPlayableWav(destination)) return destination
        val ownerDir = destination.parentFile ?: throw IOException("녹음 저장 위치가 없습니다.")
        if (!ownerDir.isDirectory && !ownerDir.mkdirs()) throw IOException("녹음 저장 폴더를 만들 수 없습니다.")
        val pending = File(ownerDir, ".${practiceId}-${UUID.randomUUID()}.pending")
        try {
            source.inputStream().use { input ->
                FileOutputStream(pending).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!isPlayableWav(pending)) throw IOException("복사한 녹음 파일이 올바르지 않습니다.")
            if (destination.exists() && !destination.delete()) throw IOException("기존 녹음 파일을 교체할 수 없습니다.")
            if (!pending.renameTo(destination)) throw IOException("녹음 파일을 저장할 수 없습니다.")
            return destination
        } finally {
            pending.delete()
        }
    }

    @Synchronized
    fun delete(ownerId: Long, practiceId: Long) {
        val file = recordingFile(ownerId, practiceId)
        if (file.exists() && !file.delete()) throw IOException("기기 녹음을 삭제할 수 없습니다.")
        file.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
    }

    @Synchronized
    fun deleteAll(ownerId: Long) {
        val directory = ownerDirectory(ownerId)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("계정 녹음을 삭제할 수 없습니다.")
    }

    private fun recordingFile(ownerId: Long, practiceId: Long): File {
        require(practiceId > 0) { "연습 기록 ID가 올바르지 않습니다." }
        return File(ownerDirectory(ownerId), "practice-$practiceId.wav")
    }

    private fun ownerDirectory(ownerId: Long): File {
        require(ownerId > 0) { "사용자 ID가 올바르지 않습니다." }
        return File(root, "user-$ownerId")
    }

    private fun isPlayableWav(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() <= 44L) return@runCatching false
        file.inputStream().use { input ->
            val header = ByteArray(12)
            input.read(header) == header.size &&
                header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    companion object { private const val DIRECTORY = "practice-recordings" }
}
