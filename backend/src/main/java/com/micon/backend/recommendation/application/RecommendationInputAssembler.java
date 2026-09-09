package com.micon.backend.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.domain.RecordingUploadStatus;
import com.micon.backend.voice.domain.VocalRangeAnalysis;
import com.micon.backend.voice.domain.VocalRangeAnalysisRepository;
import com.micon.backend.voice.domain.VocalTimbreAnalysis;
import com.micon.backend.voice.domain.VocalTimbreAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import com.micon.backend.voice.domain.VoiceRecording;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationInputAssembler {

    private final VoiceAnalysisRepository analyses;
    private final VocalRangeAnalysisRepository rangeResults;
    private final VocalTimbreAnalysisRepository timbreResults;
    private final RecommendationPreferenceReader preferences;
    private final RecommendationInputSourceProvider sourceProvider;

    public RecommendationInputAssembler(VoiceAnalysisRepository analyses,
                                        VocalRangeAnalysisRepository rangeResults,
                                        VocalTimbreAnalysisRepository timbreResults,
                                        RecommendationPreferenceReader preferences,
                                        RecommendationInputSourceProvider sourceProvider) {
        this.analyses = analyses;
        this.rangeResults = rangeResults;
        this.timbreResults = timbreResults;
        this.preferences = preferences;
        this.sourceProvider = sourceProvider;
    }

    @Transactional(readOnly = true)
    public RecommendationInput assemble(long userId, long voiceAnalysisId) {
        VoiceAnalysis analysis = analyses.findByIdAndUserId(voiceAnalysisId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        ensureAvailable(analysis);

        VocalRangeAnalysis range = rangeResults.findByVoiceAnalysisId(voiceAnalysisId).orElse(null);
        VocalTimbreAnalysis timbre = timbreResults.findByVoiceAnalysisId(voiceAnalysisId).orElse(null);
        RecommendationInput.VoiceProfile profile = profile(range, timbre);
        RecommendationPreferenceReader.Preferences preference = preferences.findByUserId(userId);
        if (preference == null) {
            preference = RecommendationPreferenceReader.Preferences.empty();
        }

        return new RecommendationInput(
                analysis.getId(),
                sourceProvider.provide(analysis, profile),
                profile,
                preference.genres(),
                preference.artists());
    }

    private void ensureAvailable(VoiceAnalysis analysis) {
        VoiceRecording recording = analysis.getRecording();
        if (analysis.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (analysis.getStatus() != VoiceAnalysisStatus.COMPLETED
                || recording == null
                || recording.getUploadStatus() != RecordingUploadStatus.UPLOADED
                || recording.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY);
        }
    }

    private RecommendationInput.VoiceProfile profile(VocalRangeAnalysis range, VocalTimbreAnalysis timbre) {
        return new RecommendationInput.VoiceProfile(
                range == null ? null : range.getStableLowMidi(),
                range == null ? null : range.getStableHighMidi(),
                range == null ? null : range.getPitchMedianMidi(),
                range == null ? null : range.getPitchDistribution(),
                range == null ? null : range.getConfidence(),
                timbre == null ? null : timbre.getTimbreLabel(),
                timbre == null ? null : timbre.getTimbreVector(),
                timbre == null ? null : timbre.getConfidence(),
                timbre == null ? null : timbre.getUserEmbedding(),
                timbre == null ? null : timbre.getEmbeddingRef());
    }
}
