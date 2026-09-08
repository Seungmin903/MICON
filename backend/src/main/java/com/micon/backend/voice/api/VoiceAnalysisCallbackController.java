package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.voice.application.AiServiceTokenValidator;
import com.micon.backend.voice.application.VoiceAnalysisCallbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/callbacks/voice-analyses")
public class VoiceAnalysisCallbackController {
    private final AiServiceTokenValidator tokenValidator;
    private final VoiceAnalysisCallbackService service;

    public VoiceAnalysisCallbackController(AiServiceTokenValidator tokenValidator, VoiceAnalysisCallbackService service) {
        this.tokenValidator = tokenValidator; this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VoiceAnalysisCallbackResponse>> accept(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody VoiceAnalysisCallbackRequest request) {
        tokenValidator.validate(authorization);
        return ResponseEntity.ok(ApiResponse.success(service.accept(request)));
    }
}
