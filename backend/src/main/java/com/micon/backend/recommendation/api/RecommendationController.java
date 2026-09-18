package com.micon.backend.domain.recommendation.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.application.RecommendationCreationService;
import com.micon.backend.domain.recommendation.application.RecommendationDispatchResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recommendations")
public class RecommendationController {
    private final RecommendationCreationService service;

    public RecommendationController(RecommendationCreationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RecommendationCreateResponse>> create(@AuthenticationPrincipal Jwt jwt) {
        RecommendationDispatchResult result = service.createFromLatestAnalysis(userId(jwt));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(RecommendationCreateResponse.from(result)));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
