package com.micon.app.data.backend.dto

/*
 * 백엔드 찜·세트리스트 계약 (backend/src/main/java/com/micon/backend/favorite/api, setlist/api).
 * 필드명은 서버 record 그대로. LocalDateTime 은 ISO 문자열("2026-09-13T21:00:00")로 온다.
 */

/** `GET /users/me/favorites` → FavoriteResponse. songs 는 SongResponse.Summary 와 같은 형태 */
data class FavoriteResponseDto(val songs: List<SongSummaryDto> = emptyList(), val totalCount: Int = 0)

/** `POST /setlists`(Create) · `PATCH /setlists/{id}`(Rename). name 1~100자 */
data class SetlistNameRequest(val name: String)

/** `PUT /setlist/{id}/song-order`. songIds 는 변경 후 전체 순서다. */
data class SetlistOrderRequest(val songIds: List<String>, val updatedAt: String?)

/** SetlistResponse.Summary */
data class SetlistSummaryDto(
    val setlistId: Long = 0,
    val name: String = "",
    val songCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** SetlistResponse.Detail */
data class SetlistDetailDto(
    val setlistId: Long = 0,
    val name: String = "",
    val songs: List<SetlistSongItemDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** SetlistResponse.SongItem */
data class SetlistSongItemDto(
    val songId: String = "",
    val title: String = "",
    val artists: List<String> = emptyList(),
    val imageUrl: String? = null,
    val durationMs: Long? = null,
    val position: Int = 0,
)
