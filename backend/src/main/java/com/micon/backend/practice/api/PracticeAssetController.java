package com.micon.backend.practice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.practice.service.PracticeAssetService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/songs")
public class PracticeAssetController {

	private final PracticeAssetService service;

	public PracticeAssetController(PracticeAssetService service) {
		this.service = service;
	}

	@GetMapping("/{songId}/practice-assets")
	public ApiResponse<PracticeAssetResponse.Detail> getPracticeAssets(
		@PathVariable @Min(1) Long songId,
		@RequestParam(defaultValue = "0") @Min(-12) @Max(12) int selectedKey
	) {
		return ApiResponse.success(service.get(songId, selectedKey));
	}

	@GetMapping("/{songId}/practice-guide")
	public ApiResponse<PracticeAssetResponse.Guide> getPracticeGuide(
		@PathVariable @Min(1) Long songId,
		@RequestParam(defaultValue = "0") @Min(-12) @Max(12) int selectedKey
	) {
		return ApiResponse.success(service.getGuide(songId, selectedKey));
	}
}
