package com.micon.backend.voice.api;

public record VoiceAnalysisComponentCallbackResponse(Long analysisId, String status, boolean duplicate) { }
