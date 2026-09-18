package com.micon.backend.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record  AuthProperties(Jwt jwt, Duration refreshTokenTtl) {

    public AuthProperties {
        if (jwt == null) {
            throw new IllegalArgumentException("auth.jwt configuration is required");
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("auth.refresh-token-ttl must be positive");
        }
    }

    public record Jwt(String issuer, String secret, Duration accessTokenTtl) {
        public Jwt {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("auth.jwt.issuer is required");
            }
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("auth.jwt.secret must be at least 32 bytes");
            }
            if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
                throw new IllegalArgumentException("auth.jwt.access-token-ttl must be positive");
            }
        }
    }
}
