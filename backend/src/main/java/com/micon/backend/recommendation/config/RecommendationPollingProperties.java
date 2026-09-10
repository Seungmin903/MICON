package com.micon.backend.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.recommendation.polling")
public record RecommendationPollingProperties(boolean enabled) {
}
