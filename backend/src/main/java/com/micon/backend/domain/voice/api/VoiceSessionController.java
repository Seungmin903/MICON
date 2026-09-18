package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.*;
import com.micon.backend.domain.voice.application.VoiceSessionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voice-analysis-sessions")
public class VoiceSessionController {
    private final VoiceSessionService service;
    public VoiceSessionController(VoiceSessionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<VoiceSessionResponse.Session>> create(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(userId(jwt))));
    }

    @GetMapping("/active")
    public ApiResponse<VoiceSessionResponse.Session> active(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken) {
        return ApiResponse.success(service.active(userId(jwt), guestToken));
    }

    @PutMapping("/{sessionId}/range-result")
    public ApiResponse<VoiceSessionResponse.Session> saveDeviceRange(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId,
            @Valid @RequestBody VoiceSessionRequest.DeviceRangeResult request) {
        return ApiResponse.success(service.saveDeviceRange(userId(jwt), guestToken, sessionId, request));
    }

    private Long userId(Jwt jwt) {
        if (jwt == null) return null;
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
