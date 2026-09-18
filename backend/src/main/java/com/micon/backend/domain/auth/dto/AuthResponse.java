package com.micon.backend.domain.auth.dto;

import com.micon.backend.domain.auth.token.TokenPair;

import java.time.Instant;

public record AuthResponse(
        Long userId,
        String email,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt
) {
    public static AuthResponse of(Long userId, String email, TokenPair tokens) {
        return new AuthResponse(
                userId,
                email,
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresAt()
        );
    }
}
