package com.micon.backend.domain.favorite.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.favorite.dto.FavoriteResponse;
import com.micon.backend.domain.favorite.service.FavoriteService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

/** 인증된 회원의 찜 목록 조회와 곡 찜·해제 HTTP 요청을 처리한다. */
@Validated
@RestController
@RequestMapping("/favorites")
public class FavoriteController {

	private final FavoriteService favoriteService;

	// 기능: 필요한 의존성과 초기값을 받아 FavoriteController 객체를 생성한다.
	public FavoriteController(FavoriteService favoriteService) {
		this.favoriteService = favoriteService;
	}

	@PutMapping("/{songId}")
	// 기능: addFavorite 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<Void>> addFavorite(
		@PathVariable @NotBlank @Size(max = 64) String songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		favoriteService.add(userId(jwt), songId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@DeleteMapping("/{songId}")
	// 기능: removeFavorite 삭제·취소 요청을 검증하고 서비스에 위임한다.
	public ResponseEntity<ApiResponse<Void>> removeFavorite(
		@PathVariable @NotBlank @Size(max = 64) String songId,
		@AuthenticationPrincipal Jwt jwt
	) {
		favoriteService.remove(userId(jwt), songId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping
	// 기능: getFavorites 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ResponseEntity<ApiResponse<FavoriteResponse>> getFavorites(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(ApiResponse.success(favoriteService.getFavorites(userId(jwt))));
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
