package com.micon.backend.domain.voice.dto;

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
                        @com.fasterxml.jackson.annotation.JsonRawValue String noteResults,
                        String protocolVersion, BigDecimal stableLowMidi, BigDecimal stableHighMidi,
                        @com.fasterxml.jackson.annotation.JsonRawValue String voiceQuality) {
        public Range(BigDecimal lowMidi, BigDecimal highMidi, String measurementStatus,
                     String qualityStatus, String noteResults, String protocolVersion,
                     BigDecimal stableLowMidi, BigDecimal stableHighMidi) {
            this(lowMidi, highMidi, measurementStatus, qualityStatus, noteResults,
                    protocolVersion, stableLowMidi, stableHighMidi, null);
        }
        public Range(BigDecimal lowMidi, BigDecimal highMidi, String measurementStatus, String qualityStatus) {
            this(lowMidi, highMidi, measurementStatus, qualityStatus, null, null, null, null, null);
        }
        public Range(BigDecimal lowMidi, BigDecimal highMidi, String measurementStatus,
                     String qualityStatus, String noteResults) {
            this(lowMidi, highMidi, measurementStatus, qualityStatus, noteResults, null, null, null, null);
        }
    }
}
