package com.micon.backend.domain.voice.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VoiceStorageProperties.class)
public class VoiceStorageConfig { }
