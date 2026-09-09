package com.micon.backend.setlist.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.setlist.service.SetlistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

@Validated
@RestController
@RequestMapping("/setlists")
public class SetlistController {

	private final SetlistService setlistService;

	public SetlistController(SetlistService setlistService) {
		this.setlistService = setlistService;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<SetlistResponse.Summary>> create(
		@Valid @RequestBody SetlistRequest.Create request,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(setlistService.create(userId(jwt), request.name())));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<SetlistResponse.Summary>>> getSetlists(
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(setlistService.getSetlists(userId(jwt))));
	}

	@GetMapping("/{setlistId}")
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> getSetlist(
		@PathVariable @Min(1) Long setlistId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(setlistService.getSetlist(userId(jwt), setlistId)));
	}

	@PatchMapping("/{setlistId}")
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
	public ResponseEntity<ApiResponse<Void>> delete(
		@PathVariable @Min(1) Long setlistId,
		@AuthenticationPrincipal Jwt jwt
	) {
		setlistService.delete(userId(jwt), setlistId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PutMapping("/{setlistId}/songs/{songId}")
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> addSong(
		@PathVariable @Min(1) Long setlistId,
		@PathVariable @Min(1) Long songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.addSong(userId(jwt), setlistId, songId)
		));
	}

	@DeleteMapping("/{setlistId}/songs/{songId}")
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> removeSong(
		@PathVariable @Min(1) Long setlistId,
		@PathVariable @Min(1) Long songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.removeSong(userId(jwt), setlistId, songId)
		));
	}

	@PutMapping("/{setlistId}/song-order")
	public ResponseEntity<ApiResponse<SetlistResponse.Detail>> reorderSongs(
		@PathVariable @Min(1) Long setlistId,
		@Valid @RequestBody SetlistRequest.Reorder request,
		@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok(ApiResponse.success(
			setlistService.reorderSongs(userId(jwt), setlistId, request.songIds(), request.updatedAt())
		));
	}

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
