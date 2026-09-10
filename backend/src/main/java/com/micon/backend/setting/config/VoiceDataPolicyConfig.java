package com.micon.backend.setting.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VoiceDataPolicyProperties.class)
public class VoiceDataPolicyConfig {
}
