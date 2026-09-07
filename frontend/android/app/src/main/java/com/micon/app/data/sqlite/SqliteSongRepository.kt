package com.micon.app.data.sqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.micon.app.data.SongAnalysis
import com.micon.app.data.SongDetail
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.data.engine.dto.KaraokeNote
import com.micon.app.data.engine.dto.RangeRatios
import com.micon.app.data.engine.dto.Register
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **임시 스텁.** assets/micon_catalog.sqlite (MySQL `micon` 덤프와 같은 컬럼, 890곡 + 50곡 분석)를
 * 첫 실행 때 filesDir 로 복사해 읽기 전용으로 연다. Room 을 쓰지 않는 이유: 스키마 검증 없이 원본 파일을
 * 그대로 쓰기 위해서. 백엔드 API 가 생기면 이 클래스와 asset 을 삭제한다.
 */
class SqliteSongRepository(private val context: Context, moshi: Moshi) : SongRepository {
    private val registerAdapter = moshi.adapter(Register::class.java)
    private val ratiosAdapter = moshi.adapter(RangeRatios::class.java)
    private val beatGridAdapter = moshi.adapter(BeatGrid::class.java)
    private val stringMapAdapter = moshi.adapter<Map<String, String>>(Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))
    private val karaokeNotesAdapter = moshi.adapter<List<KaraokeNote>>(Types.newParameterizedType(List::class.java, KaraokeNote::class.java))

    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    private suspend fun open(): SQLiteDatabase = mutex.withLock {
        db ?: run {
            val file = ensureCopied()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /** asset → filesDir 복사. 크기가 다르면(asset 갱신) 다시 복사. */
    private fun ensureCopied(): File {
        val target = File(context.filesDir, ASSET_NAME)
        val expected = runCatching { context.assets.openFd(ASSET_NAME).use { it.length } }.getOrNull()
        val stale = !target.exists() || (expected != null && target.length() != expected)
        if (stale) {
            val tmp = File(context.filesDir, "$ASSET_NAME.tmp")
            context.assets.open(ASSET_NAME).use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "sample DB copy failed" }
        }
        return target
    }

    override suspend fun search(query: String, analyzedOnly: Boolean, limit: Int, offset: Int): List<SongSummary> = withContext(Dispatchers.IO) {
        val db = open()
        val like = "%${query.trim()}%"
        val where = buildString {
            append("WHERE (s.title LIKE ? OR s.artist_name LIKE ? OR s.tj_number LIKE ?)")
            if (analyzedOnly) append(" AND a.song_id IS NOT NULL")
        }
        val sql = """
            SELECT $SUMMARY_COLUMNS, (a.song_id IS NOT NULL) AS analyzed
            FROM songs s LEFT JOIN song_analysis a ON a.song_id = s.song_id AND a.status = 'complete'
            $where
            ORDER BY analyzed DESC, s.artist_name COLLATE NOCASE, s.title COLLATE NOCASE
            LIMIT ? OFFSET ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf(like, like, like, limit.toString(), offset.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.summary()) }
        }
    }

    override suspend fun detail(songId: String): SongDetail? = withContext(Dispatchers.IO) {
        val db = open()
        val summaryAndLyrics = db.rawQuery(
            """SELECT $SUMMARY_COLUMNS, s.lyrics_plain, (a.song_id IS NOT NULL) AS analyzed
               FROM songs s LEFT JOIN song_analysis a ON a.song_id = s.song_id AND a.status = 'complete'
               WHERE s.song_id = ?""",
            arrayOf(songId),
        ).use { c -> if (c.moveToFirst()) c.summary() to c.str("lyrics_plain") else null } ?: return@withContext null

        val analysis = db.rawQuery("SELECT * FROM song_analysis WHERE song_id = ? AND status = 'complete'", arrayOf(songId)).use { c ->
            if (!c.moveToFirst()) null else SongAnalysis(
                analysisVersion = c.str("analysis_version"),
                analyzedAt = c.str("analyzed_at"),
                keyName = c.str("key_name"),
                keyConfidence = c.dbl("key_confidence"),
                tempoBpm = c.int("tempo_bpm"),
                timeSignature = c.str("time_signature"),
                lowestMidi = c.int("lowest_midi"),
                highestMidi = c.int("highest_midi"),
                coreLowMidi = c.int("core_low_midi"),
                coreHighMidi = c.int("core_high_midi"),
                rangeRatios = c.str("range_ratios")?.let { json -> runCatching { ratiosAdapter.fromJson(json) }.getOrNull() },
                vocalStartSec = c.dbl("vocal_start_sec"),
                vocalEndSec = c.dbl("vocal_end_sec"),
                voicedRatio = c.dbl("voiced_ratio"),
                noteCount = c.int("note_count"),
                rangeConfidence = c.int("range_confidence"),
                taskSupport = c.str("task_support")?.let { json -> runCatching { stringMapAdapter.fromJson(json) }.getOrNull() },
                register = c.str("register")?.let { json -> runCatching { registerAdapter.fromJson(json) }.getOrNull() },
                beatGrid = c.str("beat_grid")?.let { json -> runCatching { beatGridAdapter.fromJson(json) }.getOrNull() },
                scoreOctaveShift = c.int("score_octave_shift"),
            )
        }
        val artifacts = db.rawQuery("SELECT kind, semitones FROM song_artifacts WHERE song_id = ?", arrayOf(songId)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }
        SongDetail(
            summary = summaryAndLyrics.first,
            lyricsPlain = summaryAndLyrics.second,
            analysis = analysis,
            hasInstrumental = artifacts.any { it.first == "instrumental" },
            renderedKeyShifts = artifacts.filter { it.first == "instrumental-key" }.map { it.second }.sorted(),
        )
    }

    override suspend fun karaokeNotes(songId: String): List<KaraokeNote>? = withContext(Dispatchers.IO) {
        val db = open()
        val json = db.rawQuery("SELECT karaoke_notes FROM song_notes WHERE song_id = ?", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return@withContext null
        karaokeNotesAdapter.fromJson(json)
    }

    override suspend fun counts(): SongRepository.Counts = withContext(Dispatchers.IO) {
        val db = open()
        val songs = db.rawQuery("SELECT COUNT(*) FROM songs", null).use { it.moveToFirst(); it.getInt(0) }
        val analyzed = db.rawQuery("SELECT COUNT(*) FROM song_analysis WHERE status = 'complete'", null).use { it.moveToFirst(); it.getInt(0) }
        SongRepository.Counts(songs, analyzed)
    }

    private fun Cursor.summary() = SongSummary(
        id = getString(getColumnIndexOrThrow("song_id")),
        tjNumber = str("tj_number"),
        title = str("title") ?: "",
        artist = str("artist_name") ?: "",
        album = str("album_name"),
        durationMs = int("duration_ms"),
        imageUrl = str("display_image_url"),
        genre = str("primary_genre"),
        language = str("primary_language"),
        releaseDate = str("release_date"),
        analyzed = (int("analyzed") ?: 0) != 0,
    )

    private fun Cursor.str(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.dbl(column: String): Double? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getDouble(it) }

    companion object {
        const val ASSET_NAME = "micon_catalog.sqlite"
        private const val SUMMARY_COLUMNS =
            "s.song_id, s.tj_number, s.title, s.artist_name, s.album_name, s.duration_ms, s.display_image_url, s.primary_genre, s.primary_language, s.release_date"
    }
}
