package com.micon.backend.setting.api;

public record VoiceDataPolicyResponse(
	String policyVersion,
	String collectionPurpose,
	String retentionPeriod,
	String deletionPolicy
) {
}
