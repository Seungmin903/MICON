package com.micon.backend.domain.health.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.health.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 모바일 앱과 외부 클라이언트가 백엔드 연결 상태를 확인하는 공개 API다. */
@RestController
public class HealthController {

	@GetMapping("/health")
	public ApiResponse<HealthResponse> health() {
		return ApiResponse.success(new HealthResponse("UP"));
	}
}
