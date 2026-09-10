package com.micon.backend.favorite.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.favorite.service.FavoriteService;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/users/me/favorites")
public class FavoriteController {

	private final FavoriteService favoriteService;

	public FavoriteController(FavoriteService favoriteService) {
		this.favoriteService = favoriteService;
	}

	@PutMapping("/{songId}")
	public ResponseEntity<ApiResponse<Void>> addFavorite(
		@PathVariable @Min(1) Long songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		favoriteService.add(userId(jwt), songId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@DeleteMapping("/{songId}")
	public ResponseEntity<ApiResponse<Void>> removeFavorite(
		@PathVariable @Min(1) Long songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		favoriteService.remove(userId(jwt), songId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<FavoriteResponse>> getFavorites(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(ApiResponse.success(favoriteService.getFavorites(userId(jwt))));
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
