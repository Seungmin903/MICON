package com.micon.backend.domain.practice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.practice.service.PracticeAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 곡별 연습 리소스와 예상 난관을 조회하는 연습 준비 API를 제공한다. */
@Validated
@RestController
@RequestMapping("/songs")
@Tag(name = "연습 준비", description = "연습 시작 전에 필요한 반주·가사·목표 음정과 예상 난관 조회 API")
public class PracticeAssetController {

	private final PracticeAssetService service;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeAssetController 객체를 생성한다.
	public PracticeAssetController(PracticeAssetService service) {
		this.service = service;
	}

	@Operation(summary = "연습 리소스 조회", description = "선택한 키에 맞춘 반주, 가사, 목표 음정과 연습 구간을 조회합니다.")
	@GetMapping("/{songId}/practice-assets")
	// 기능: getPracticeAssets 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<PracticeAssetResponse.Detail> getPracticeAssets(
		@PathVariable("songId") @NotBlank @Size(max = 64) String songId,
		@Parameter(description = "원키 기준 반음 조절값(-6~+5)")
		@RequestParam(name = "selectedKey", defaultValue = "0") @Min(-6) @Max(5) int selectedKey
	) {
		return ApiResponse.success(service.get(songId, selectedKey));
	}

	@Operation(summary = "MR 조회", description = "곡 ID와 선택한 키에 맞는 반주 MR의 재생 URL을 조회합니다.")
	@GetMapping("/{songId}/mr")
	// 기능: 곡 ID와 선택 키로 재생 가능한 MR을 조회한다.
	public ApiResponse<PracticeAssetResponse.Instrumental> getMr(
		@PathVariable("songId") @NotBlank @Size(max = 64) String songId,
		@Parameter(description = "원키 기준 반음 조절값(-6~+5)")
		@RequestParam(name = "selectedKey", defaultValue = "0") @Min(-6) @Max(5) int selectedKey
	) {
		return ApiResponse.success(service.getMr(songId, selectedKey));
	}

	@Operation(summary = "연습 예상 난관 조회", description = "선택한 키를 기준으로 근거가 있는 고음, 긴 호흡, 빠른 가사, 음정 도약 구간을 조회합니다.")
	@GetMapping("/{songId}/practice-guide")
	// 기능: getPracticeGuide 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<PracticeAssetResponse.Guide> getPracticeGuide(
		@PathVariable("songId") @NotBlank @Size(max = 64) String songId,
		@Parameter(description = "원키 기준 반음 조절값(-6~+5)")
		@RequestParam(name = "selectedKey", defaultValue = "0") @Min(-6) @Max(5) int selectedKey
	) {
		return ApiResponse.success(service.getGuide(songId, selectedKey));
	}
}
