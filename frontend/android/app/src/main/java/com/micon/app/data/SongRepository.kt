package com.micon.app.data

import com.micon.app.data.backend.dto.BeatGrid
import com.micon.app.data.backend.dto.KaraokeNote

/**
 * 곡 카탈로그 접근 인터페이스. 화면·ViewModel 은 이 인터페이스만 본다.
 *
 * 구현: [com.micon.app.data.backend.RemoteSongRepository] — 앱 백엔드(Spring, MySQL `micon`) REST.
 * 곡 식별자는 백엔드 `song.song_id`(VARCHAR, UUID 등 문자열) 하나다. TJ 번호·카탈로그 track_id 는 앱에서 쓰지 않는다(팀 결정 2026-09-10).
 * 엔드포인트: `GET /songs`, `GET /songs/{id}`, `GET /songs/{id}/practice-assets` (docs/openapi.yaml, backend `SongController`·`PracticeAssetController`).
 */
interface SongRepository {
    /** 제목·가수 검색. query 가 비면 전체. 서버 페이지 크기 최대 100. */
    suspend fun search(query: String = "", page: Int = 0, size: Int = 50): SongPage

    /** 장르가 선택되면 해당 장르만 검색한다. 기존 구현체는 장르 미지원 시 전체 검색으로 동작한다. */
    suspend fun search(query: String, page: Int, size: Int, genreId: Long?): SongPage =
        search(query, page, size)

    /** 앱 수명 캐시를 가진 구현체가 수동 새로고침 때 해당 첫 페이지를 비운다. */
    fun invalidateSearchCache(query: String, size: Int, genreId: Long?) = Unit

    /** 곡 상세. 없는 곡(404)은 null. */
    suspend fun detail(songId: String): SongDetail?

    /**
     * 연습에 필요한 데이터 한 묶음(반주·목표 음정·가사·구간).
     * 준비되지 않은 곡은 [PracticeAssetsNotReadyException](409), 미리 렌더되지 않은 키는 [UnsupportedPracticeKeyException](422).
     */
    suspend fun practiceAssets(songId: String, selectedKey: Int = 0): PracticeAssets

    /**
     * 키 전환에 필요한 반주 한 건. 원격 구현은 가벼운 `/songs/{id}/mr` API를 사용한다.
     * 테스트/로컬 구현은 별도 구현 없이 기존 연습 리소스 조회로 동작한다.
     */
    suspend fun instrumental(songId: String, selectedKey: Int): InstrumentalMedia =
        practiceAssets(songId, selectedKey).instrumental

    /** [practiceAssets] 의 목표 음정만. 준비되지 않은 곡은 null. */
    suspend fun karaokeNotes(songId: String): List<KaraokeNote>? =
        try { practiceAssets(songId).targetNotes } catch (_: PracticeAssetsNotReadyException) { null }
}

/** 백엔드 `PageResponse` 그대로 */
data class SongPage(
    val items: List<SongSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 목록 항목 (백엔드 `SongResponse.Summary`). 상세에서만 오는 값은 nullable. */
data class SongSummary(
    /** 백엔드 song.song_id */
    val id: String,
    val title: String,
    /** 참여 가수 이름을 `, ` 로 이은 표시용 문자열 */
    val artist: String,
    val imageUrl: String?,
    val durationMs: Long?,
    val album: String? = null,
    val genre: String? = null,
    /** yyyy-MM-dd */
    val releaseDate: String? = null,
)

/**
 * 곡 분석 값. 백엔드 상세에는 원키·BPM·음역만 있고, 박자표·비트 그리드는 `practice-assets` 확장 후 채워진다(그때까지 null).
 * 화면은 null 을 "값 없음" 으로 다루고 기본값으로 대체한다.
 */
data class SongAnalysis(
    val keyName: String?,
    val tempoBpm: Int?,
    val timeSignature: String?,
    val lowestMidi: Int?,
    val highestMidi: Int?,
    val coreLowMidi: Int? = null,
    val coreHighMidi: Int? = null,
    /** pitch_range / timbre / rhythm / vocal_identity → pass|review|fail|not_analyzed. 백엔드 미제공 시 null */
    val taskSupport: Map<String, String>? = null,
    val beatGrid: BeatGrid? = null,
    val noteCount: Int? = null,
)

/** 곡 상세 (백엔드 `SongResponse.Detail`) */
data class SongDetail(
    val summary: SongSummary,
    val genres: List<String>,
    /** 원키·BPM·음역. 백엔드가 분석 값을 주지 않는 곡은 null */
    val analysis: SongAnalysis?,
    /** 내가 찜한 곡인가 */
    val favorite: Boolean,
    /** 반주·음표·가사가 준비되어 연습 가능한가 (`song.practice_ready`) */
    val practiceReady: Boolean,
) {
    val hasInstrumental: Boolean get() = practiceReady
}

/** `GET /songs/{id}/practice-assets` 응답 (백엔드 `PracticeAssetResponse.Detail`) */
data class PracticeAssets(
    val songId: String,
    val title: String,
    val selectedKey: Int,
    val instrumental: InstrumentalMedia,
    val lyricsPlain: String?,
    /** LRC 텍스트(`[mm:ss.xx] 가사` 줄). 없으면 null */
    val lyricsSynced: String?,
    /** 목표 음정 = `song_note.karaoke_notes` (엔진 `result.karaokeNotes` 와 같은 형식) */
    val targetNotes: List<KaraokeNote>,
    val dataVersion: String?,
    val sections: List<PracticeSection>,
    // 아래 4개는 백엔드 확장 요청 항목. 응답에 없으면 null (연습 화면은 기본값 4/4·100 BPM 으로 동작).
    val keyName: String? = null,
    val tempoBpm: Int? = null,
    val timeSignature: String? = null,
    val beatGrid: BeatGrid? = null,
)

/**
 * 반주 미디어 (`song_media` 행). [filePath] 는 S3 object key 라 앱이 직접 열 수 없다.
 * 재생은 백엔드가 발급하는 만료 URL [playbackUrl] 로만 한다 — 백엔드 응답에 아직 없으면 null 이고 연습 화면은 "반주 준비 중" 으로 안내한다.
 */
data class InstrumentalMedia(
    val mediaId: Long?,
    val filePath: String?,
    val mimeType: String?,
    val fileBytes: Long?,
    val sha256: String?,
    val playbackUrl: String?,
    /** ISO-8601. 이 시각 60초 전부터 URL을 새로 발급한다. */
    val playbackUrlExpiresAt: String? = null,
)

/** 곡 구간 (`song_section`). 시간은 ms. */
data class PracticeSection(
    val sectionId: Long,
    /** INTRO | VOCAL | INTERLUDE | VERSE | PRE_CHORUS | CHORUS | BRIDGE | OUTRO | CUSTOM */
    val sectionType: String,
    val sectionName: String,
    val startMs: Int,
    val endMs: Int,
    val sequenceNo: Int,
    /** NONE | HIGH_NOTE | LONG_BREATH | FAST_LYRICS | PITCH_JUMP | MIXED */
    val difficultyType: String,
    val difficultyScore: Double?,
    val description: String?,
)

/** 연습 데이터가 아직 없는 곡 (백엔드 409 `PRACTICE_ASSET_NOT_READY`) */
class PracticeAssetsNotReadyException(message: String) : Exception(message)

/** 미리 렌더되지 않은 키 (백엔드 422 `UNSUPPORTED_PRACTICE_KEY`) */
class UnsupportedPracticeKeyException(message: String) : Exception(message)
