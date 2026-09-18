package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.application.VoiceDataDeletionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/voice-data-deletions")
public class VoiceDataDeletionController {
    private final VoiceDataDeletionService service;

    public VoiceDataDeletionController(VoiceDataDeletionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VoiceDataDeletionResponse.Result>> deleteAll(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VoiceDataDeletionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.deleteAll(userId(jwt))));
    }

    @GetMapping("/{deletionId}")
    public ResponseEntity<ApiResponse<VoiceDataDeletionResponse.State>> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long deletionId) {
        return ResponseEntity.ok(ApiResponse.success(service.status(userId(jwt), deletionId)));
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
