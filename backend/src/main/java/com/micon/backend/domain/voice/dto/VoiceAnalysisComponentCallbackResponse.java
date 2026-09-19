package com.micon.backend.domain.voice.dto;

public record VoiceAnalysisComponentCallbackResponse(Long analysisId, String status, boolean duplicate) { }
