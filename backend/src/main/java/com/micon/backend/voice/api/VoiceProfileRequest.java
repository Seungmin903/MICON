package com.micon.backend.voice.api;

import jakarta.validation.constraints.Min;

public final class VoiceProfileRequest {
    private VoiceProfileRequest() {}
    public record Save(@Min(1) long resultVersion) {}
}
