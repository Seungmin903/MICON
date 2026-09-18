package com.micon.backend.domain.voice.api;

public record VoiceAnalysisComponentCallbackResponse(Long analysisId, String status, boolean duplicate) { }
