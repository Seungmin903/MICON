package com.micon.backend.voice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record VoiceOnboardingResultResponse(
        long sessionId, String status, long resultVersion, String rangeStatus,
        String timbreStatus, boolean saveAllowed, boolean savedProfileExists,
        LocalDateTime expiresAt, VoiceSessionResponse.Range range,
        List<Component> components, List<Artist> artists) {
    public record Component(String component, String status, String failureCode, String failureReason) {}
    public record Artist(long artistId, String name, String imageUrl, BigDecimal similarity) {}
}
