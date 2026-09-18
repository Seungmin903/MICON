package com.micon.backend.domain.voice.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice.storage")
public record VoiceStorageProperties(String bucket, String region, String prefix, long maxFileBytes) {
    public VoiceStorageProperties {
        bucket = bucket == null ? "" : bucket;
        region = region == null || region.isBlank() ? "ap-northeast-2" : region;
        prefix = prefix == null || prefix.isBlank() ? "voice-recordings" : prefix;
        maxFileBytes = maxFileBytes <= 0 ? 26_214_400L : maxFileBytes;
    }
}
