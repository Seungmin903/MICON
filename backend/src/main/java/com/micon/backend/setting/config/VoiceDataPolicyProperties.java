package com.micon.backend.setting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application 설정의 음성 데이터 수집·보관·삭제 정책 문구를 타입 안전하게 바인딩한다. */
@ConfigurationProperties(prefix = "policy.voice-data")
public record VoiceDataPolicyProperties(
	String version,
	String collectionPurpose,
	String retentionPeriod,
	String deletionPolicy
) {
}
