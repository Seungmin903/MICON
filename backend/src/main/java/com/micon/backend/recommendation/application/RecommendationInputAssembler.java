package com.micon.backend.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.user.domain.UserStatus;
import com.micon.backend.voice.domain.UserVoiceProfile;
import com.micon.backend.voice.domain.UserVoiceProfileRepository;
import com.micon.backend.voice.domain.VoiceModelResult;
import com.micon.backend.voice.domain.VoiceModelResultRepository;
import com.micon.backend.voice.domain.VoiceRangeMeasurement;
import com.micon.backend.voice.domain.VoiceRangeMeasurementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationInputAssembler {

    private final UserVoiceProfileRepository profiles;
    private final VoiceRangeMeasurementRepository rangeResults;
    private final VoiceModelResultRepository modelResults;
    private final RecommendationPreferenceReader preferences;

    public RecommendationInputAssembler(UserVoiceProfileRepository profiles,
                                        VoiceRangeMeasurementRepository rangeResults,
                                        VoiceModelResultRepository modelResults,
                                        RecommendationPreferenceReader preferences) {
        this.profiles = profiles;
        this.rangeResults = rangeResults;
        this.modelResults = modelResults;
        this.preferences = preferences;
    }

    @Transactional(readOnly = true)
    public RecommendationInput assemble(long userId, long profileId) {
        UserVoiceProfile savedProfile = profiles.findByIdAndUserId(profileId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        ensureAvailable(savedProfile);

        long sessionId = savedProfile.getSession().getId();
        VoiceRangeMeasurement range = rangeResults.findBySessionId(sessionId).orElse(null);
        VoiceModelResult ecapa = modelResults.findBySessionIdAndModelKey(sessionId, "ECAPA").orElse(null);
        RecommendationInput.VoiceProfile profile = profile(range, ecapa);
        if (profile.embeddingRef() == null || profile.embeddingRef().isBlank()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY);
        }
        RecommendationPreferenceReader.Preferences preference = preferences.findByUserId(userId);
        if (preference == null) {
            preference = RecommendationPreferenceReader.Preferences.empty();
        }

        return new RecommendationInput(
                savedProfile.getId(),
                savedProfile.getSession().getSongId(),
                profile,
                preference.genres(),
                preference.artists());
    }

    private void ensureAvailable(UserVoiceProfile profile) {
        if (profile.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private RecommendationInput.VoiceProfile profile(VoiceRangeMeasurement range, VoiceModelResult ecapa) {
        return new RecommendationInput.VoiceProfile(
                range == null ? null : range.getFullLowMidi(),
                range == null ? null : range.getFullHighMidi(),
                null,
                range == null ? null : range.getNoteResults(),
                null,
                null, null, null, null,
                ecapa == null ? null : ecapa.getVectorObjectKey(),
                null, null, null, null);
    }
}
