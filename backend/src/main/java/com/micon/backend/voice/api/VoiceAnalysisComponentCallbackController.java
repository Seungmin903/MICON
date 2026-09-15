package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.voice.application.AiServiceTokenValidator;
import com.micon.backend.voice.application.VoiceAnalysisComponentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/callbacks/voice-analysis-components")
@Tag(name = "AI 음성 분석 콜백", description = "AI 서버 전용 음성 분석 결과 접수 API입니다.")
public class VoiceAnalysisComponentCallbackController {
    private final AiServiceTokenValidator tokenValidator;
    private final VoiceAnalysisComponentCallbackService service;

    public VoiceAnalysisComponentCallbackController(AiServiceTokenValidator tokenValidator,
                                                    VoiceAnalysisComponentCallbackService service) {
        this.tokenValidator = tokenValidator;
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "AI 음성 분석 결과 접수",
            description = "서비스 토큰과 작업 ID를 검증하고 ECAPA·SINGER_BYOL 임베딩 또는 ARTIST_SEARCH 유사 가수 결과를 저장합니다. WAV는 받지 않습니다.")
    public ApiResponse<VoiceAnalysisComponentCallbackResponse> accept(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody VoiceAnalysisComponentCallbackRequest request) {
        tokenValidator.validate(authorization);
        return ApiResponse.success(service.accept(request));
    }
}
