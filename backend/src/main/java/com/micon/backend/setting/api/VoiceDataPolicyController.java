package com.micon.backend.setting.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.setting.config.VoiceDataPolicyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceDataPolicyController {

	private final VoiceDataPolicyProperties properties;

	public VoiceDataPolicyController(VoiceDataPolicyProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/policies")
	public ApiResponse<VoiceDataPolicyResponse> getPolicy() {
		return ApiResponse.success(new VoiceDataPolicyResponse(
			properties.version(),
			properties.collectionPurpose(),
			properties.retentionPeriod(),
			properties.deletionPolicy()
		));
	}
}
