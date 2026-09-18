package com.micon.backend.domain.auth.dto;

import java.time.Instant;

public final class PasswordResetResponse {
    private PasswordResetResponse() {
    }

    public record Issued(String requestId, Instant expiresAt) {
    }

    public record Verified(String resetToken, Instant expiresAt) {
    }
}
