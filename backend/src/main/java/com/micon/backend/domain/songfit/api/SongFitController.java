package com.micon.backend.domain.songfit.api;

import com.micon.backend.domain.songfit.dto.SongFitResponse;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.songfit.service.SongFitService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/songs/{songId}/fit")
public class SongFitController {

	private final SongFitService songFitService;

	public SongFitController(SongFitService songFitService) {
		this.songFitService = songFitService;
	}

	@GetMapping
	public ApiResponse<SongFitResponse> getSongFit(
		@PathVariable @NotBlank @Size(max = 64) String songId,
		@RequestParam @Min(1) Long analysisId,
		@RequestParam(defaultValue = "0") @Min(-3) @Max(3) int keyOffset,
		Principal principal
	) {
		Long userId = Long.valueOf(principal.getName());
		return ApiResponse.success(songFitService.getSongFit(userId, songId, analysisId, keyOffset));
	}
}
