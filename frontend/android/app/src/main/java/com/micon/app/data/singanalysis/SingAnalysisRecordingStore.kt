package com.micon.app.data.singanalysis

import android.content.Context
import android.util.AtomicFile
import com.micon.app.data.requireSongId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 저장이 끝난 녹음(WAV + 메타 JSON). */
data class SavedRecording(val meta: SingRecordingMeta, val wav: File, val json: File) {
    val id: String get() = meta.id
}

/**
 * 불러서 분석하기 녹음의 로컬 저장소: `filesDir/sing-analysis/<id>.wav` + `<id>.json`.
 *
 * - 녹음은 처음부터 이 디렉터리의 WAV 에 직접 쓴다(임시 파일 이동 없음). JSON 이 있어야 "저장된 녹음" 이고,
 *   JSON 없는 WAV 는 중단된 녹음이므로 [deleteOrphans] 가 지운다.
 * - 저장 후 [keep] 쌍만 남기고 오래된 것부터 지운다(16 kHz 모노 4분 ≈ 7.7 MB, 48 kHz 폴백이면 3배).
 *   업로드 연동이 끝나면 더 줄여도 된다.
 * - 분석 담당자는 [list] 로 녹음을 읽거나, 화면이 넘겨주는 [SavedRecording] 을 [SongAnalysisSubmitter] 에서 받는다.
 */
class SingAnalysisRecordingStore(context: Context, private val keep: Int = DEFAULT_KEEP, storageDirectory: File? = null) {
    private val dir = (storageDirectory ?: File(context.filesDir, DIR_NAME)).apply { mkdirs() }
    private var closed = false

    fun newId(songId: String): String = "sing-${requireSongId(songId)}-${java.util.UUID.randomUUID()}"

    fun wavFile(id: String) = File(dir, "$id.wav")
    fun metaFile(id: String) = File(dir, "$id.json")

    /** 메타를 원자적으로 쓰고 오래된 녹음을 정리한다. IO 스레드에서 호출할 것. */
    @Synchronized fun save(meta: SingRecordingMeta): SavedRecording {
        check(!closed) { "종료된 녹음 세션입니다." }
        val wav = wavFile(meta.id)
        require(wav.isFile && wav.length() > WAV_HEADER_BYTES) { "녹음 파일이 없습니다: ${wav.name}" }
        writeMeta(meta)
        prune()
        return SavedRecording(meta, wav, metaFile(meta.id))
    }

    /** 이미 저장된 녹음의 메타만 다시 쓴다(서버 id 기록 등). WAV 는 그대로, 정리도 하지 않는다. IO 스레드에서 호출할 것. */
    fun update(meta: SingRecordingMeta): SavedRecording {
        val wav = wavFile(meta.id)
        require(wav.isFile) { "녹음 파일이 없습니다: ${wav.name}" }
        writeMeta(meta)
        return SavedRecording(meta, wav, metaFile(meta.id))
    }

    private fun writeMeta(meta: SingRecordingMeta) {
        val atomic = AtomicFile(metaFile(meta.id))
        val out = atomic.startWrite()
        try {
            out.write(SingRecordingMetaCodec.toJson(meta).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** 저장된 녹음 전체, 최신순. 깨진 JSON 은 건너뛴다. */
    fun list(): List<SavedRecording> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { json ->
                val meta = runCatching { AtomicFile(json).openRead().bufferedReader().use { it.readText() } }.getOrNull()?.let(SingRecordingMetaCodec::fromJson)
                    ?: return@mapNotNull null
                val wav = wavFile(meta.id)
                if (!wav.isFile) return@mapNotNull null
                SavedRecording(meta, wav, json)
            }
            .sortedByDescending { it.meta.recordedAt }

    fun delete(id: String) {
        AtomicFile(metaFile(id)).delete()
        AtomicFile(File(dir, "$id.wav.submission.json")).delete()
        wavFile(id).delete()
    }

    /** JSON 이 없는 WAV(중단된 녹음) 삭제. 화면 진입 시, 새 녹음을 만들기 전에 부른다. */
    fun deleteOrphans() {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.forEach { wav ->
            if (!File(dir, wav.nameWithoutExtension + ".json").exists()) wav.delete()
        }
    }

    /** 최신 [keep] 쌍만 남긴다. */
    fun prune() {
        list().drop(keep).forEach { delete(it.id) }
    }

    @Synchronized fun closeTemporary() { closed = true; dir.deleteRecursively() }

    companion object {
        const val DIR_NAME = "sing-analysis"
        const val DEFAULT_KEEP = 5
        private const val WAV_HEADER_BYTES = 44L
        private val ID_FORMAT get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
