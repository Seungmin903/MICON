package com.micon.backend.domain.auth.dto;

import com.micon.backend.domain.auth.token.TokenPair;

import java.time.Instant;

public record TokenRefreshResponse(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt
) {
    public static TokenRefreshResponse from(TokenPair tokens) {
        return new TokenRefreshResponse(
                tokens.accessToken(), tokens.refreshToken(), tokens.accessTokenExpiresAt());
    }
}
