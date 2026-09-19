package com.micon.backend.domain.setting.api;

import com.micon.backend.domain.setting.dto.VoiceDataPolicyResponse;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.setting.config.VoiceDataPolicyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 여부와 무관하게 현재 음성 데이터 이용 정책을 제공한다. */
@RestController
public class VoiceDataPolicyController {

	private final VoiceDataPolicyProperties properties;

	// 기능: 필요한 의존성과 초기값을 받아 VoiceDataPolicyController 객체를 생성한다.
	public VoiceDataPolicyController(VoiceDataPolicyProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/policies")
	// 기능: getPolicy 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<VoiceDataPolicyResponse> getPolicy() {
		return ApiResponse.success(new VoiceDataPolicyResponse(
			properties.version(),
			properties.collectionPurpose(),
			properties.retentionPeriod(),
			properties.deletionPolicy()
		));
	}
}
