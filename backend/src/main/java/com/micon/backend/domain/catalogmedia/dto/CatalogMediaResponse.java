package com.micon.backend.domain.catalogmedia.dto;

import java.time.Instant;

public record CatalogMediaResponse(
        long mediaId,
        String songId,
        String catalogTrackId,
        String mediaType,
        String objectKey,
        String contentType,
        Long fileBytes,
        String sha256,
        String contentUrl,
        Instant expiresAt
) {
}
