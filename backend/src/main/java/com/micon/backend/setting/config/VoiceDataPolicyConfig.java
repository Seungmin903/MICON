package com.micon.backend.setting.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** VoiceDataPolicyProperties를 Spring 빈으로 등록하는 설정 클래스다. */
@Configuration
@EnableConfigurationProperties(VoiceDataPolicyProperties.class)
public class VoiceDataPolicyConfig {
}
