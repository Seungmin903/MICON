package com.micon.app.data.recommend.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 추천 서버(ai/recommend-server, 8788) 응답. `schemas.py` 와 1:1 — 원본은 `docs/response-schema.json`,
 * 값 예시는 `docs/sample-response.json`(테스트 픽스처 `fixtures/recommend-sample.json` 으로 복사).
 * 서버 필드는 snake_case, 앱은 camelCase 라 @Json 으로 잇는다. 모르는 필드는 Moshi 가 무시한다.
 */
@JsonClass(generateAdapter = false)
data class RecommendJobDto(
    val id: String,
    val status: String,                       // queued | processing | complete | failed
    val progress: Int = 0,
    val message: String? = null,
    val mode: String? = null,
    val genres: List<String> = emptyList(),
    val result: RecommendResultDto? = null,
    val error: String? = null,
) {
    val isComplete: Boolean get() = status == "complete"
    val isFailed: Boolean get() = status == "failed"
}

@JsonClass(generateAdapter = false)
data class RecommendResultDto(
    val algorithm: AlgorithmDto? = null,
    /** 업로드한 녹음에서 서버가 관찰한 사용자 음역 프로파일. 리포트의 "나의 음역대"가 이 값으로 채워진다 */
    val query: QueryDto? = null,
    /** 응답의 중심(shelves-v1). 섹션마다 기준이 다르고 곡이 겹치지 않는다 */
    val shelves: List<ShelfDto> = emptyList(),
    /** shelves[0].items 복사본 — 이전 클라이언트 호환용 */
    val recommendations: List<RecommendationDto> = emptyList(),
    @Json(name = "catalog_track_count") val catalogTrackCount: Int? = null,
)

@JsonClass(generateAdapter = false)
data class QueryDto(
    val pitchProfile: PitchProfileDto? = null,
    val pitchError: String? = null,
)

/**
 * 서버 `pitch_profile.summarize_pitch_profile` 요약 (histogram 은 서버가 응답에서 뺀다).
 * reliable=false 면 음역 계산이 꺼진 응답이므로 프로필을 덮어쓰지 않는다.
 */
@JsonClass(generateAdapter = false)
data class PitchProfileDto(
    val lowestMidi: Int,
    val highestMidi: Int,
    val coreLowMidi: Int,
    val medianMidi: Int,
    val coreHighMidi: Int,
    val reliable: Boolean = false,
    val qualityReasons: List<String> = emptyList(),
    val confidence: Float? = null,
    val voicedSeconds: Float? = null,
    val noteCount: Int? = null,
)

/** 추천 섹션 하나. key: voice | original_key | favorite_artists | challenge | discovery */
@JsonClass(generateAdapter = false)
data class ShelfDto(
    val key: String,
    val title: String,
    val metric: String? = null,
    val items: List<RecommendationDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class AlgorithmDto(
    @Json(name = "scoring_version") val scoringVersion: String? = null,
    @Json(name = "shelves_version") val shelvesVersion: String? = null,
    @Json(name = "applied_mode") val appliedMode: String? = null,
    val composition: CompositionDto? = null,
    @Json(name = "language_gate") val languageGate: LanguageGateDto? = null,
)

@JsonClass(generateAdapter = false)
data class CompositionDto(val version: String? = null, val order: String? = null)

@JsonClass(generateAdapter = false)
data class LanguageGateDto(val enabled: Boolean = false, val allowed: List<String>? = null)

@JsonClass(generateAdapter = false)
data class RecommendationDto(
    val position: Int,
    /** 소속 섹션 key. 호환용 recommendations[] 에도 들어 있다 */
    val shelf: String? = null,
    @Json(name = "track_id") val trackId: String,
    @Json(name = "song_id") val songId: String?,          // 백엔드 song_id (숫자 문자열). null 이거나 숫자가 아니면 앱이 열 수 없는 곡
    val title: String,
    val artist: String,
    val metadata: TrackMetadataDto? = null,
    val axis: String? = null,                            // ECAPA | SINGER_BYOL | CONSENSUS — voice 섹션에서만, 나머지는 null
    val label: String? = null,
    @Json(name = "axis_scores") val axisScores: AxisScoresDto? = null,
    val confidence: Float? = null,
    @Json(name = "timbre_score") val timbreScore: Float,
    /** 섹션별 표시 지표 (coverage0 · center0 · artist_similarity · high_ratio0 · genre …) */
    val metrics: Map<String, Any?> = emptyMap(),
    @Json(name = "range_fit") val rangeFit: RangeFitDto? = null,
    @Json(name = "recommended_key") val recommendedKey: Int? = null,
    @Json(name = "final_score") val finalScore: Float,
    @Json(name = "scoring_version") val scoringVersion: String,
    @Json(name = "composition_version") val compositionVersion: String,
)

@JsonClass(generateAdapter = false)
data class TrackMetadataDto(
    @Json(name = "album_name") val albumName: String? = null,
    @Json(name = "display_image_url") val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,                        // ko | en | jp
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "tj_id") val tjId: String? = null,
    /** female | male | mixed. 성별 필터용 자리 — 카탈로그에 아직 데이터 없음(null) */
    @Json(name = "artist_gender") val artistGender: String? = null,
)

@JsonClass(generateAdapter = false)
data class AxisScoresDto(val ecapa: Float, @Json(name = "singer_byol") val singerByol: Float, val consensus: Float)

/** 서버 range_fit (camelCase 그대로). 나머지 필드는 무시. */
@JsonClass(generateAdapter = false)
data class RangeFitDto(
    val scoringVersion: String,
    val score: Float,
    val coverage: Float,
    val centerScore: Float,
    val spanFit: Float,
    val softFit: Float,
    val keyShift: Int,
    val songCoreLowMidi: Int? = null,
    val songCoreHighMidi: Int? = null,
    val catalogProfileReliable: Boolean = true,
)

@JsonClass(generateAdapter = false)
data class RecommendStatusDto(
    val ready: Boolean,
    val device: String? = null,
    val catalogTracks: Int? = null,
    val missing: List<String> = emptyList(),
)
