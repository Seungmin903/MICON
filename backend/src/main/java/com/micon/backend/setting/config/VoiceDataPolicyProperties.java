package com.micon.backend.setting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "policy.voice-data")
public record VoiceDataPolicyProperties(
	String version,
	String collectionPurpose,
	String retentionPeriod,
	String deletionPolicy
) {
}
