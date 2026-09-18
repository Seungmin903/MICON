package com.micon.backend.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.password-reset")
public record PasswordResetProperties(
        Duration codeTtl,
        Duration tokenTtl,
        int maxAttempts,
        String mailFrom
) {
    public PasswordResetProperties {
        if (codeTtl == null || codeTtl.isNegative() || codeTtl.isZero()) {
            throw new IllegalArgumentException("auth.password-reset.code-ttl must be positive");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("auth.password-reset.token-ttl must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("auth.password-reset.max-attempts must be positive");
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            throw new IllegalArgumentException("auth.password-reset.mail-from is required");
        }
    }
}
