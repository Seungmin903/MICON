package com.micon.backend.domain.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.recommendation.polling")
public record RecommendationPollingProperties(boolean enabled, java.time.Duration maxWait, Integer maxConcurrent) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RecommendationPollingProperties {
        if (maxWait == null) maxWait = java.time.Duration.ofMinutes(10);
        if (maxWait.isNegative() || maxWait.isZero()) throw new IllegalArgumentException("maxWait must be positive");
        if (maxConcurrent == null) maxConcurrent = 4;
        if (maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException("maxConcurrent must be between 1 and 16");
        }
    }
    public RecommendationPollingProperties(boolean enabled) { this(enabled, java.time.Duration.ofMinutes(10), 4); }
}
