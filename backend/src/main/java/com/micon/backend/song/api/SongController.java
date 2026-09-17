package com.micon.backend.song.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.song.service.SongService;
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

/** 곡 목록 검색과 곡 상세 조회 HTTP 요청을 받아 SongService에 전달한다. */
@Validated
@RestController
@RequestMapping("/songs")
public class SongController {

	private final SongService songService;

	// 기능: 필요한 의존성과 초기값을 받아 SongController 객체를 생성한다.
	public SongController(SongService songService) {
		this.songService = songService;
	}

	@GetMapping
	// 기능: searchSongs 요청을 검증하고 서비스 처리 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<PageResponse<SongResponse.Summary>> searchSongs(
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(songService.search(query, page, size));
	}

	@GetMapping("/{songId}")
	// 기능: getSong 요청을 검증하고 서비스 처리 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<SongResponse.Detail> getSong(
		@PathVariable @NotBlank @Size(max = 64) String songId,
		Principal principal
	) {
		Long userId = principal == null ? null : Long.valueOf(principal.getName());
		return ApiResponse.success(songService.getDetail(songId, userId));
	}
}
