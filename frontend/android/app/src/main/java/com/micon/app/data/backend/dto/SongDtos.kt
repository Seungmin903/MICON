package com.micon.app.data.backend.dto


/**
 * 곡 API 응답 DTO. 필드 이름은 백엔드 `SongResponse`·`PageResponse`·`PracticeAssetResponse` 와 1:1 (camelCase 그대로).
 * Moshi 리플렉션 어댑터로 파싱하므로 없을 수 있는 값은 기본값을 둔다.
 */
data class PageDto<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)

data class SongSummaryDto(
    val songId: Long,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val imageUrl: String? = null,
    val durationMs: Long? = null,
)

data class SongDetailDto(
    val songId: Long,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val albumName: String? = null,
    val imageUrl: String? = null,
    val releaseDate: String? = null,
    val durationMs: Long? = null,
    val originalKey: String? = null,
    val bpm: Double? = null,
    val genres: List<String> = emptyList(),
    val lowestMidi: Double? = null,
    val highestMidi: Double? = null,
    val favorite: Boolean = false,
    val practiceReady: Boolean = false,
)

data class PracticeAssetsDto(
    val songId: Long,
    val title: String = "",
    val selectedKey: Int = 0,
    val instrumental: MediaDto? = null,
    val lyricsPlain: String? = null,
    /** 백엔드 `JsonNode` — 현재 DB 는 LRC 문자열을 JSON 문자열로 저장한다. 다른 형태면 무시(null). */
    val lyricsSynced: Any? = null,
    /** 백엔드 `JsonNode` — `karaoke_notes` 배열 */
    val targetNotes: List<KaraokeNote>? = null,
    val dataVersion: String? = null,
    val sections: List<SectionDto> = emptyList(),
    // 백엔드 확장 요청 항목 (없으면 null)
    val keyName: String? = null,
    val tempoBpm: Double? = null,
    val timeSignature: String? = null,
    val beatGrid: BeatGrid? = null,
)

data class MediaDto(
    val mediaId: Long? = null,
    val filePath: String? = null,
    val mimeType: String? = null,
    val fileBytes: Long? = null,
    val sha256: String? = null,
    /** 백엔드 확장 요청 항목: 만료 있는 재생 URL */
    val playbackUrl: String? = null,
)

data class SectionDto(
    val sectionId: Long,
    val sectionType: String = "CUSTOM",
    val sectionName: String = "",
    val startMs: Int = 0,
    val endMs: Int = 0,
    val sequenceNo: Int = 0,
    val difficultyType: String = "NONE",
    val difficultyScore: Double? = null,
    val confidence: Double? = null,
    val description: String? = null,
)
