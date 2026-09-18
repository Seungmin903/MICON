package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.application.VoiceOnboardingResultService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voice-analysis-sessions/{sessionId}")
public class VoiceOnboardingResultController {
    private final VoiceOnboardingResultService service;
    public VoiceOnboardingResultController(VoiceOnboardingResultService service) { this.service = service; }

    @GetMapping("/result")
    public ApiResponse<VoiceOnboardingResultResponse> result(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long sessionId) {
        return ApiResponse.success(service.result(userId(jwt), sessionId));
    }

    @PostMapping("/profile")
    public ResponseEntity<ApiResponse<VoiceProfileResponse>> save(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long sessionId, @Valid @RequestBody VoiceProfileRequest.Save request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.save(userId(jwt), sessionId, request)));
    }

    private long userId(Jwt jwt) {
        if (jwt == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
