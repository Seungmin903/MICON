package com.micon.backend.voice.api;

import com.micon.backend.voice.domain.VoiceAnalysisStatus;

public record VoiceAnalysisCallbackResponse(long voiceAnalysisId, VoiceAnalysisStatus status, boolean duplicate) {
}
