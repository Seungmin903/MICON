package com.micon.backend.setlist.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.setlist.service.SetlistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원의 세트리스트와 포함 곡을 관리하는 HTTP API 진입점이다. */
@Validated
@RestController
@RequestMapping("/setlist")
public class SetlistController {

	private final SetlistService setlistService;

	// 기능: 필요한 의존성과 초기값을 받아 SetlistController 객체를 생성한다.
	public SetlistController(SetlistService setlistService) {
		this.setlistService = setlistService;
	}

	@PostMapping
	// 기능: create 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Summary>> create(
		@Valid @RequestBody SetlistRequest.Create request,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(setlistService.create(userId(jwt), request.name())));
	}

	@GetMapping
	// 기능: getSetlists 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ResponseEntity<ApiResponse<List<SetlistResponse.Summary>>> getSetlists(
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(setlistService.getSetlists(userId(jwt))));
	}

	@GetMapping("/{setlistId}")
	// 기능: getSetlist 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> getSetlist(
		@PathVariable @Min(1) Long setlistId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(setlistService.getSetlist(userId(jwt), setlistId)));
	}

	@PatchMapping("/{setlistId}")
	// 기능: rename 변경 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Summary>> rename(
		@PathVariable @Min(1) Long setlistId,
		@Valid @RequestBody SetlistRequest.Rename request,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.rename(userId(jwt), setlistId, request.name())
		));
	}

	@DeleteMapping("/{setlistId}")
	// 기능: delete 삭제·취소 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<Void>> delete(
		@PathVariable @Min(1) Long setlistId,
		@AuthenticationPrincipal Jwt jwt
	) {
		setlistService.delete(userId(jwt), setlistId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PostMapping("/{setlistId}/{songId}")
	// 기능: addSong 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> addSong(
		@PathVariable @Min(1) Long setlistId,
		@PathVariable @NotBlank @Size(max = 64) String songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.addSong(userId(jwt), setlistId, songId)
		));
	}

	@DeleteMapping("/{setlistId}/{songId}")
	// 기능: removeSong 삭제·취소 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> removeSong(
		@PathVariable @Min(1) Long setlistId,
		@PathVariable @NotBlank @Size(max = 64) String songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.removeSong(userId(jwt), setlistId, songId)
		));
	}

	@PutMapping("/{setlistId}/song-order")
	// 기능: reorderSongs 변경 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> reorderSongs(
		@PathVariable @Min(1) Long setlistId,
		@Valid @RequestBody SetlistRequest.Reorder request,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.reorderSongs(userId(jwt), setlistId, request.songIds(), request.updatedAt())
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
