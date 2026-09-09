package com.micon.backend.recommendation.application;

import com.micon.backend.voice.domain.VoiceAnalysis;

public interface RecommendationInputSourceProvider {
    RecommendationInput.Source provide(VoiceAnalysis analysis, RecommendationInput.VoiceProfile voiceProfile);
}
