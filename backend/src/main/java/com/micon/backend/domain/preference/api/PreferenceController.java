package com.micon.backend.domain.preference.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.preference.service.PreferenceService;
import com.micon.backend.domain.song.api.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
public class PreferenceController {

	private final PreferenceService preferenceService;

	public PreferenceController(PreferenceService preferenceService) {
		this.preferenceService = preferenceService;
	}

	@GetMapping("/genres")
	public ApiResponse<List<PreferenceResponse.Genre>> getGenres(@AuthenticationPrincipal Jwt jwt) {
		return ApiResponse.success(preferenceService.getGenres(userId(jwt)));
	}

	@GetMapping("/artists")
	public ApiResponse<PageResponse<PreferenceResponse.Artist>> getArtists(
		@AuthenticationPrincipal Jwt jwt,
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "30") int size
	) {
		return ApiResponse.success(preferenceService.getArtists(userId(jwt), query, page, size));
	}

	@GetMapping("/users/me/preferences")
	public ApiResponse<PreferenceResponse> getPreferences(@AuthenticationPrincipal Jwt jwt) {
		return ApiResponse.success(preferenceService.getPreferences(userId(jwt)));
	}

	@PutMapping("/users/me/preferences")
	public ApiResponse<PreferenceResponse> savePreferences(
		@AuthenticationPrincipal Jwt jwt,
		@Valid @RequestBody PreferenceRequest request
	) {
		return ApiResponse.success(preferenceService.savePreferences(userId(jwt), request));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleInvalidQuery(MethodArgumentTypeMismatchException exception) {
		ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
		return ResponseEntity.status(errorCode.getStatus())
			.body(ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
	}

	private long userId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		try {
			return Long.parseLong(jwt.getSubject());
		} catch (NumberFormatException exception) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
	}
}
