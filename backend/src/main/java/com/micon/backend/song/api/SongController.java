package com.micon.backend.song.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.song.service.SongService;
import jakarta.validation.constraints.Max;
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
@RequestMapping("/songs")
public class SongController {

	private final SongService songService;

	public SongController(SongService songService) {
		this.songService = songService;
	}

	@GetMapping
	public ApiResponse<PageResponse<SongResponse.Summary>> searchSongs(
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(songService.search(query, page, size));
	}

	@GetMapping("/{songId}")
	public ApiResponse<SongResponse.Detail> getSong(
		@PathVariable @Min(1) Long songId,
		Principal principal
	) {
		Long userId = principal == null ? null : Long.valueOf(principal.getName());
		return ApiResponse.success(songService.getDetail(songId, userId));
	}
}
