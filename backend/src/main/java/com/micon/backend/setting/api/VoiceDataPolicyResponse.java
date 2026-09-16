package com.micon.backend.setting.api;

/** 앱에 공개하는 음성 데이터 수집 목적·보관 기간·삭제 정책 응답이다. */
public record VoiceDataPolicyResponse(
	String policyVersion,
	String collectionPurpose,
	String retentionPeriod,
	String deletionPolicy
) {
}
