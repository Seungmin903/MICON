package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.application.ArtistSearchRetryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voice-analysis-sessions/{sessionId}/analysis/{analysisId}/components/ARTIST_SEARCH/retry")
public class ArtistSearchRetryController {
    private final ArtistSearchRetryService service;

    public ArtistSearchRetryController(ArtistSearchRetryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<VoiceAnalysisJobResponse>> retry(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long sessionId, @PathVariable long analysisId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.retry(userId(jwt), sessionId, analysisId)));
    }

    private long userId(Jwt jwt) {
        if (jwt == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
