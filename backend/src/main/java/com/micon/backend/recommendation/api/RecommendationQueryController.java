package com.micon.backend.recommendation.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.application.RecommendationQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recommendations")
public class RecommendationQueryController {
    private final RecommendationQueryService service;

    public RecommendationQueryController(RecommendationQueryService service) {
        this.service = service;
    }

    @GetMapping("/{recommendationId}")
    public ResponseEntity<ApiResponse<RecommendationResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long recommendationId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(userId(jwt), recommendationId)));
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<RecommendationResponse>> latest(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(service.latest(userId(jwt))));
    }

    @GetMapping("/{recommendationId}/songs/{songId}")
    public ResponseEntity<ApiResponse<RecommendationResponse.Song>> song(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long recommendationId,
            @PathVariable String songId) {
        return ResponseEntity.ok(ApiResponse.success(service.song(userId(jwt), recommendationId, songId)));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
