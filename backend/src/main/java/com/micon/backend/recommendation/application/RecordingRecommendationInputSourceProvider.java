package com.micon.backend.recommendation.application;

import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceRecording;
import org.springframework.stereotype.Component;

@Component
public class RecordingRecommendationInputSourceProvider implements RecommendationInputSourceProvider {

    @Override
    public RecommendationInput.Source provide(VoiceAnalysis analysis, RecommendationInput.VoiceProfile voiceProfile) {
        VoiceRecording recording = analysis.getRecording();
        return new RecommendationInput.RecordingSource(
                recording.getId(), recording.getStoragePath(), recording.getMediaType());
    }
}
