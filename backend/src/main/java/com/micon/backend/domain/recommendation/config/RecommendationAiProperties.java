package com.micon.backend.domain.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.recommendation")
public record RecommendationAiProperties(
        String baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration readTimeout
) {
    public RecommendationAiProperties {
        baseUrl = valueOrDefault(baseUrl, "http://localhost:8788");
        serviceToken = serviceToken == null ? "" : serviceToken;
        connectTimeout = durationOrDefault(connectTimeout, Duration.ofSeconds(3));
        readTimeout = durationOrDefault(readTimeout, Duration.ofSeconds(10));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Duration durationOrDefault(Duration value, Duration defaultValue) {
        return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
    }
}
