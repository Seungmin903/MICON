package com.micon.backend.recommendation.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.recommendation.service.RecommendationService;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

	private final RecommendationService recommendationService;

	public RecommendationController(RecommendationService recommendationService) {
		this.recommendationService = recommendationService;
	}

	@GetMapping("/latest")
	public ApiResponse<RecommendationResponse.Detail> getLatestRecommendation(
		@RequestParam(required = false) String mode,
		@RequestParam(defaultValue = "rank") String sort,
		Principal principal
	) {
		return ApiResponse.success(recommendationService.getLatest(userId(principal), mode, sort));
	}

	@GetMapping("/{recommendationId}")
	public ApiResponse<RecommendationResponse.Detail> getRecommendation(
		@PathVariable @Min(1) Long recommendationId,
		@RequestParam(defaultValue = "rank") String sort,
		Principal principal
	) {
		return ApiResponse.success(recommendationService.get(userId(principal), recommendationId, sort));
	}

	@GetMapping("/{recommendationId}/songs/{songId}")
	public ApiResponse<RecommendationResponse.SongDetail> getRecommendedSong(
		@PathVariable @Min(1) Long recommendationId,
		@PathVariable @Min(1) Long songId,
		Principal principal
	) {
		return ApiResponse.success(recommendationService.getSong(userId(principal), recommendationId, songId));
	}

	private Long userId(Principal principal) {
		return Long.valueOf(principal.getName());
	}
}
