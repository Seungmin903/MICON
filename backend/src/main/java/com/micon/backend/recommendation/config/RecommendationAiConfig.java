package com.micon.backend.recommendation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RecommendationAiProperties.class, RecommendationPollingProperties.class})
public class RecommendationAiConfig {
}
