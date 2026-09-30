package com.micon.backend.domain.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.client.VoiceStreamGateway;
import com.micon.backend.domain.voice.dto.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.dto.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.service.StreamVoiceAnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voice-analysis-sessions/{sessionId}/analysis/streams/{streamId}")
public class VoiceStreamController {
    private final StreamVoiceAnalysisService service;

    public VoiceStreamController(StreamVoiceAnalysisService service) { this.service = service; }

    @PostMapping
    public ApiResponse<VoiceStreamGateway.State> begin(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId, @PathVariable String streamId,
            @Valid @RequestBody Begin request) {
        return ApiResponse.success(service.begin(userId(jwt), guestToken, sessionId, streamId, request.sampleRate()));
    }

    @GetMapping
    public ApiResponse<VoiceStreamGateway.State> status(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId, @PathVariable String streamId) {
        return ApiResponse.success(service.status(userId(jwt), guestToken, sessionId, streamId));
    }

    @PutMapping(value = "/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<VoiceStreamGateway.State> chunk(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId, @PathVariable String streamId, @PathVariable int index,
            @RequestBody byte[] pcm) {
        return ApiResponse.success(service.chunk(userId(jwt), guestToken, sessionId, streamId, index, pcm));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<VoiceAnalysisJobResponse>> complete(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Guest-Session-Token", required = false) String guestToken,
            @PathVariable long sessionId, @PathVariable String streamId,
            @Valid @RequestBody Complete request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                service.complete(userId(jwt), guestToken, sessionId, streamId,
                        request.pcmSha256(), request.meta())));
    }

    public record Begin(int sampleRate) { }
    public record Complete(@Pattern(regexp = "[0-9a-f]{64}") String pcmSha256,
                           @Valid VoiceAnalysisUploadRequest meta) { }

    private Long userId(Jwt jwt) {
        if (jwt == null) return null;
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
