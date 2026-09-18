package com.micon.backend.domain.auth.token;

import java.time.Instant;

public record TokenPair(String accessToken, String refreshToken, Instant accessTokenExpiresAt) {
}
