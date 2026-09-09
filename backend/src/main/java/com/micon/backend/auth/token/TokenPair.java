package com.micon.backend.auth.token;

import java.time.Instant;

public record TokenPair(String accessToken, String refreshToken, Instant accessTokenExpiresAt) {
}
