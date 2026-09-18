package com.micon.app.data.backend.dto

/*
 * 백엔드 추천·곡 적합도 계약 (backend/src/main/java/com/micon/backend/recommendation/api, songfit/api).
 * 필드명은 서버 record 그대로. BigDecimal 은 Double, JsonNode 는 Any, LocalDateTime 은 ISO 문자열.
 */

/** `POST /recommendations`(202) → RecommendationCreateResponse. 최근 COMPLETED 음성 분석으로 추천 생성을 시작한다. */
data class RecommendationCreateDto(
    val recommendationId: Long = 0,
    val jobId: String? = null,
    /** GENERATING */
    val status: String? = null,
    val progress: Int = 0,
    val generatedAt: String? = null,
)

/** `GET /recommendations/latest`·`/{id}` → RecommendationResponse */
data class RecommendationDto(
    val recommendationId: Long = 0,
    val jobId: String? = null,
    /** GENERATING · COMPLETED · EMPTY · FAILED */
    val status: String? = null,
    val progress: Int = 0,
    val failureCode: String? = null,
    val failureReason: String? = null,
    val algorithmVersion: String? = null,
    val scoringVersion: String? = null,
    val compositionVersion: String? = null,
    val generatedAt: String? = null,
    val completedAt: String? = null,
    val expiresAt: String? = null,
    val shelves: List<RecommendationShelfDto> = emptyList(),
    val profileId: Long? = null,
    val sourceSessionId: Long? = null,
    val profileVersion: Long? = null,
)

/** RecommendationResponse.Shelf. key: voice · original_key · favorite_artists · challenge · discovery */
data class RecommendationShelfDto(
    val key: String = "",
    val title: String = "",
    val metric: String? = null,
    val order: Int = 0,
    val items: List<RecommendationSongDto> = emptyList(),
)

/** RecommendationResponse.Song — 곡 id 만 있고 제목·가수는 없다(앱이 곡 카탈로그에서 채운다). */
data class RecommendationSongDto(
    val songId: String = "",
    val overallRank: Int = 0,
    val position: Int = 0,
    /** ECAPA · SINGER_BYOL · CONSENSUS (voice 섹션), 그 외 null */
    val axis: String? = null,
    val label: String? = null,
    val recommendedKey: Int? = null,
    val finalScore: Double? = null,
    val timbreScore: Double? = null,
    val rangeFit: Any? = null,
    val metrics: Any? = null,
    val reasonText: String? = null,
    val scoringVersion: String? = null,
    val compositionVersion: String? = null,
)

/** `GET /songs/{songId}/fit?analysisId&keyOffset` → SongFitResponse */
data class SongFitDto(
    val songId: String = "",
    val analysisId: Long? = null,
    val selectedKeyOffset: Int = 0,
    /** AVAILABLE · UNAVAILABLE */
    val evaluationStatus: String? = null,
    val originalKeyFit: SongFitRangeDto? = null,
    val selectedKeyFit: SongFitRangeDto? = null,
    val recommendedKeyOffset: Int? = null,
    val recommendationScores: SongFitScoresDto? = null,
    val reason: String? = null,
    val burdenSections: List<BurdenSectionDto> = emptyList(),
    /** UNAVAILABLE 사유: USER_STABLE_RANGE_UNAVAILABLE · SONG_RANGE_UNAVAILABLE */
    val unavailableReason: String? = null,
)

/** SongFitResponse.RangeFit. score 는 0~100(겹침 비율). */
data class SongFitRangeDto(
    val userStableLowMidi: Double? = null,
    val userStableHighMidi: Double? = null,
    val songLowMidi: Double? = null,
    val songHighMidi: Double? = null,
    val score: Double? = null,
)

/** SongFitResponse.RecommendationScores — 추천 결과 행이 있을 때만 */
data class SongFitScoresDto(
    val overall: Double? = null,
    val range: Double? = null,
    val timbre: Double? = null,
    val rhythm: Double? = null,
    val algorithmVersion: String? = null,
    /** Optional, from a completed recommendation for this same saved profile. */
    val timbreAxis: String? = null,
    val timbreLabel: String? = null,
)

/** SongFitResponse.BurdenSection */
data class BurdenSectionDto(
    val sectionId: Long? = null,
    val sectionType: String? = null,
    val sectionName: String? = null,
    val startMs: Int = 0,
    val endMs: Int = 0,
    val difficultyType: String? = null,
    val difficultyScore: Double? = null,
    val description: String? = null,
)
