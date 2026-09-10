package com.micon.backend.setting.api;

import jakarta.validation.constraints.NotNull;

public final class SettingRequest {

	private SettingRequest() {
	}

	public record Update(
		@NotNull Boolean saveOriginalRecording
	) {
	}
}
