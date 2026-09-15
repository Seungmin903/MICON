package com.micon.backend.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.recommendation.polling")
public record RecommendationPollingProperties(boolean enabled, java.time.Duration maxWait) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RecommendationPollingProperties {
        if (maxWait == null) maxWait = java.time.Duration.ofMinutes(10);
        if (maxWait.isNegative() || maxWait.isZero()) throw new IllegalArgumentException("maxWait must be positive");
    }
    public RecommendationPollingProperties(boolean enabled) { this(enabled, java.time.Duration.ofMinutes(10)); }
}
