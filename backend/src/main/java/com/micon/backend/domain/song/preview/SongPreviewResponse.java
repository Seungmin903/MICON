package com.micon.backend.domain.song.preview;

import java.time.Instant;

/** 앱이 직접 S3 키를 알 필요 없이 재생에만 쓰는 짧은 만료 URL 응답이다. */
public record SongPreviewResponse(
        String songId,
        int previewSeconds,
        String playbackUrl,
        Instant playbackUrlExpiresAt
) {
}
