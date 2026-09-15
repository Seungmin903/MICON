package com.micon.backend.voice.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class VoiceSessionResponse {
    private VoiceSessionResponse() {}
    public record Session(long sessionId, String guestSessionToken, String status,
                          String rangeStatus, String timbreStatus, String artistSearchStatus,
                          long resultVersion, boolean savedProfileExists, boolean recoverable,
                          LocalDateTime expiresAt, Range range) {}
    public record Range(BigDecimal lowMidi, BigDecimal highMidi, String measurementStatus,
                        String qualityStatus,
                        @com.fasterxml.jackson.annotation.JsonRawValue String noteResults) {
        public Range(BigDecimal lowMidi, BigDecimal highMidi, String measurementStatus, String qualityStatus) {
            this(lowMidi, highMidi, measurementStatus, qualityStatus, null);
        }
    }
}
