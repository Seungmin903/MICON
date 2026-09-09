package com.micon.backend.catalogmedia;

import java.time.Instant;

public record CatalogMediaResponse(
        long mediaId,
        long songId,
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
