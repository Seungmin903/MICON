package com.micon.backend.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google ID 토큰 검증에 쓰는 공개 OAuth client 설정이다. */
@ConfigurationProperties(prefix = "auth.google")
public record GoogleAuthProperties(String webClientId, String jwkSetUri) {

    public boolean enabled() {
        return webClientId != null && !webClientId.isBlank();
    }
}
