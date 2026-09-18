package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.application.RangeAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/voice-analysis-sessions/{sessionId}/range-analysis")
@Tag(name = "음역 분석", description = "음역 측정 WAV를 저장하지 않고 AI 서버로 전달합니다.")
public class RangeAnalysisController {
    private final RangeAnalysisService service;

    public RangeAnalysisController(RangeAnalysisService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "음역 녹음 분석 요청",
            description = "WAV와 guided-range-v7 메타데이터를 AI에 직접 전달합니다.")
    public ResponseEntity<ApiResponse<VoiceAnalysisJobResponse>> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") VoiceSessionRequest.RangeAnalysisUpload request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.submit(userId(jwt), guestToken, sessionId, file, request)));
    }

    private Long userId(Jwt jwt) {
        if (jwt == null) return null;
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
