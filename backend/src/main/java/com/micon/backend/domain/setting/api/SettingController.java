package com.micon.backend.domain.setting.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.setting.service.SettingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원의 설정 조회와 변경을 처리하는 HTTP API 진입점이다. */
@RestController
@RequestMapping("/users/me/settings")
public class SettingController {

	private final SettingService settingService;

	// 기능: 필요한 의존성과 초기값을 받아 SettingController 객체를 생성한다.
	public SettingController(SettingService settingService) {
		this.settingService = settingService;
	}

	@GetMapping
	// 기능: getSettings 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ResponseEntity<ApiResponse<SettingResponse>> getSettings(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(ApiResponse.success(settingService.getSettings(userId(jwt))));
	}

	@PatchMapping
	// 기능: updateSettings 변경 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SettingResponse>> updateSettings(
		@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody SettingRequest.Update request
	) {
		return ResponseEntity.ok(ApiResponse.success(
			settingService.updateSettings(userId(jwt), request.saveOriginalRecording())
		));
	}

	// 기능: 인증 토큰에서 현재 사용자 ID를 추출하고 유효성을 확인한다.
	private long userId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		try {
			return Long.parseLong(jwt.getSubject());
		} catch (NumberFormatException exception) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
	}
}
