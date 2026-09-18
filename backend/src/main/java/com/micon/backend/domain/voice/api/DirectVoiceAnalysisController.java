package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.application.DirectVoiceAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/voice-analysis-sessions/{sessionId}/analysis")
@Tag(name = "음성 분석", description = "원본 녹음을 보관하지 않고 AI 분석 작업을 관리합니다.")
public class DirectVoiceAnalysisController {
    private final DirectVoiceAnalysisService service;

    public DirectVoiceAnalysisController(DirectVoiceAnalysisService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "녹음 파일 분석 요청",
            description = "WAV를 S3나 BE 저장소에 보관하지 않고 AI에 중계합니다. AI가 원음과 requestId를 영속화하고 QUEUED를 반환한 뒤에만 202를 반환합니다.")
    public ResponseEntity<ApiResponse<VoiceAnalysisJobResponse>> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Guest-Session-Token",required=false) String guestToken,
            @PathVariable long sessionId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") VoiceAnalysisUploadRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.submit(userId(jwt), guestToken, sessionId, file, request)));
    }

    @GetMapping
    @Operation(summary = "음성 분석 상태 조회",
            description = "세션의 최근 분석 상태와 실패 사유, 재업로드 필요 여부를 조회합니다.")
    public ApiResponse<VoiceAnalysisJobResponse> status(
            @AuthenticationPrincipal Jwt jwt, @RequestHeader(value="X-Guest-Session-Token",required=false) String guestToken, @PathVariable long sessionId) {
        return ApiResponse.success(service.status(userId(jwt), guestToken, sessionId));
    }

    @PostMapping("/{analysisId}/cancel")
    @Operation(summary = "음성 분석 취소",
            description = "진행 중인 분석을 취소하고 AI 작업 취소를 요청합니다.")
    public ApiResponse<VoiceAnalysisJobResponse> cancel(
            @AuthenticationPrincipal Jwt jwt, @RequestHeader(value="X-Guest-Session-Token",required=false) String guestToken, @PathVariable long sessionId,
            @PathVariable long analysisId) {
        return ApiResponse.success(service.cancel(userId(jwt), guestToken, sessionId, analysisId));
    }

    @PostMapping("/{analysisId}/components/{component}/retry")
    @Operation(summary = "실패한 음색 분석 컴포넌트 재시도")
    public ResponseEntity<ApiResponse<VoiceAnalysisJobResponse>> retryComponent(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long sessionId,
            @PathVariable long analysisId, @PathVariable String component) {
        if (jwt == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                service.retryComponent(userId(jwt), sessionId, analysisId, component)));
    }

    private Long userId(Jwt jwt) {
        if (jwt == null) return null;
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
