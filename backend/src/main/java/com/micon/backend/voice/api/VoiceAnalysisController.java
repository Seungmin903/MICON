package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.application.VoiceAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/voice-analyses")
public class VoiceAnalysisController {
    private final VoiceAnalysisService service;

    public VoiceAnalysisController(VoiceAnalysisService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VoiceAnalysisResponse>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VoiceAnalysisRequest request) {
        VoiceAnalysisResponse response = service.create(userId(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<ApiResponse<VoiceAnalysisResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(userId(jwt), analysisId)));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
