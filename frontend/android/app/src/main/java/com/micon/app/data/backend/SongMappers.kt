package com.micon.app.data.backend

import com.micon.app.data.SongSummary
import com.micon.app.data.backend.dto.SongSummaryDto

/** 백엔드 `SongResponse.Summary` → 앱 곡 요약. 곡 목록·찜 목록·플레이리스트가 같은 형태를 받는다. */
internal fun SongSummaryDto.toSummary() = SongSummary(
    id = songId, title = title, artist = artists.joinToString(", "), imageUrl = imageUrl, durationMs = durationMs,
)
