package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.application.VoiceAnalysisService;
import com.micon.backend.voice.application.VoiceAnalysisReportService;
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
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/voice-analyses")
public class VoiceAnalysisController {
    private final VoiceAnalysisService service;
    private final VoiceAnalysisReportService reports;

    public VoiceAnalysisController(VoiceAnalysisService service, VoiceAnalysisReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<VoiceAnalysisHistoryResponse.Page>> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(reports.history(userId(jwt), page, size)));
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<VoiceAnalysisHistoryResponse.Summary>> latest(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(reports.latest(userId(jwt))));
    }

    @GetMapping("/{analysisId}/report")
    public ResponseEntity<ApiResponse<VoiceAnalysisReportResponse>> report(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(reports.report(userId(jwt), analysisId)));
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
