package com.micon.backend.domain.voice.api;

import com.micon.backend.domain.voice.dto.VoiceProfileResponse;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.voice.service.VoiceOnboardingResultService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/voice-profile")
public class VoiceProfileController {
    private final VoiceOnboardingResultService service;

    public VoiceProfileController(VoiceOnboardingResultService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<VoiceProfileResponse> latest(@AuthenticationPrincipal Jwt jwt) {

        return ApiResponse.success(service.latestProfile(userId(jwt)));
    }

    private long userId(Jwt jwt) {
        if (jwt == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }
}
