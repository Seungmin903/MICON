package com.micon.app.data

import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.data.engine.dto.KaraokeNote
import com.micon.app.data.engine.dto.RangeRatios
import com.micon.app.data.engine.dto.Register

/**
 * 곡 카탈로그 접근 인터페이스. 화면·ViewModel 은 이 인터페이스만 본다.
 *
 * 현재 구현: [com.micon.app.data.sqlite.SqliteSongRepository] (assets 의 개발용 샘플 DB).
 * 앱 백엔드(MySQL 8 `micon`) REST API 가 생기면 Retrofit 구현으로 교체하고 sqlite 구현·asset 은 삭제한다.
 * 컬럼 의미는 docs/micon_schema.sql (songs, song_analysis, song_notes, song_artifacts).
 */
interface SongRepository {
    /** 제목·가수 검색. query 가 비면 전체. analyzedOnly = 분석(song_analysis complete) 된 곡만. */
    suspend fun search(query: String = "", analyzedOnly: Boolean = false, limit: Int = 100, offset: Int = 0): List<SongSummary>

    suspend fun detail(songId: String): SongDetail?

    /** song_notes.karaoke_notes (= 엔진 result.karaokeNotes). 분석 안 된 곡은 null. */
    suspend fun karaokeNotes(songId: String): List<KaraokeNote>?

    suspend fun counts(): Counts

    data class Counts(val songs: Int, val analyzed: Int)
}

/** songs 테이블의 목록용 컬럼 */
data class SongSummary(
    val id: String,              // songs.song_id = 엔진 job id (tj-<번호>)
    val tjNumber: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int?,
    val imageUrl: String?,
    val genre: String?,
    val language: String?,
    val releaseDate: String?,
    val analyzed: Boolean,
)

/** song_analysis 한 행 (JSON 컬럼은 DTO 로 풀어 둠) */
data class SongAnalysis(
    val analysisVersion: String?,
    val analyzedAt: String?,
    val keyName: String?,
    val keyConfidence: Double?,
    val tempoBpm: Int?,
    val timeSignature: String?,
    val lowestMidi: Int?,
    val highestMidi: Int?,
    val coreLowMidi: Int?,
    val coreHighMidi: Int?,
    val rangeRatios: RangeRatios?,
    val vocalStartSec: Double?,
    val vocalEndSec: Double?,
    val voicedRatio: Double?,
    val noteCount: Int?,
    val rangeConfidence: Int?,
    val taskSupport: Map<String, String>?,   // pitch_range / timbre / rhythm / vocal_identity → pass|review|fail|not_analyzed
    val register: Register?,
    val beatGrid: BeatGrid?,
    val scoreOctaveShift: Int?,
)

data class SongDetail(
    val summary: SongSummary,
    val lyricsPlain: String?,
    val analysis: SongAnalysis?,
    /** song_artifacts 에 instrumental 이 있는가 (엔진 스트리밍 가능) */
    val hasInstrumental: Boolean,
    /** 미리 렌더된 키 변경 반주 semitones 목록 */
    val renderedKeyShifts: List<Int>,
)
