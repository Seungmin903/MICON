package com.micon.backend.songfit.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.songfit.service.SongFitService;
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
@RequestMapping("/songs/{songId}/fit")
public class SongFitController {

	private final SongFitService songFitService;

	public SongFitController(SongFitService songFitService) {
		this.songFitService = songFitService;
	}

	@GetMapping
	public ApiResponse<SongFitResponse> getSongFit(
		@PathVariable @Min(1) Long songId,
		@RequestParam @Min(1) Long analysisId,
		@RequestParam(defaultValue = "0") int keyOffset,
		Principal principal
	) {
		Long userId = Long.valueOf(principal.getName());
		return ApiResponse.success(songFitService.getSongFit(userId, songId, analysisId, keyOffset));
	}
}
