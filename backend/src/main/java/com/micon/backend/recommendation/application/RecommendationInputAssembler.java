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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RecommendationInputAssembler {

    private final UserVoiceProfileRepository profiles;
    private final VoiceRangeMeasurementRepository rangeResults;
    private final VoiceModelResultRepository modelResults;
    private final RecommendationPreferenceReader preferences;
    private final ObjectMapper objectMapper;

    public RecommendationInputAssembler(UserVoiceProfileRepository profiles,
                                        VoiceRangeMeasurementRepository rangeResults,
                                        VoiceModelResultRepository modelResults,
                                        RecommendationPreferenceReader preferences,
                                        ObjectMapper objectMapper) {
        this.profiles = profiles;
        this.rangeResults = rangeResults;
        this.modelResults = modelResults;
        this.preferences = preferences;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RecommendationInput assemble(long userId, long profileId) {
        UserVoiceProfile savedProfile = profiles.findByIdAndUserId(profileId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        ensureAvailable(savedProfile);

        long sessionId = savedProfile.getSession().getId();
        VoiceRangeMeasurement range = rangeResults.findBySessionId(sessionId).orElse(null);
        VoiceModelResult ecapa = modelResults.findBySessionIdAndModelKey(sessionId, "ECAPA").orElse(null);
        VoiceModelResult singerByol = modelResults.findBySessionIdAndModelKey(sessionId, "SINGER_BYOL").orElse(null);
        RecommendationInput.VoiceProfile profile = profile(range, ecapa, singerByol);
        if (profile.userEmbedding() == null || profile.userEmbedding().isBlank()) {
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

    private RecommendationInput.VoiceProfile profile(VoiceRangeMeasurement range, VoiceModelResult ecapa,
                                                       VoiceModelResult singerByol) {
        return new RecommendationInput.VoiceProfile(
                range == null ? null : range.getFullLowMidi(),
                range == null ? null : range.getFullHighMidi(),
                null,
                range == null ? null : range.getNoteResults(),
                null,
                null, null, null,
                userEmbedding(ecapa, singerByol),
                null,
                null, null, null, null);
    }

    private String userEmbedding(VoiceModelResult ecapa, VoiceModelResult singerByol) {
        if (!valid(ecapa, 192) || !valid(singerByol, 1000)) return null;
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("ECAPA", modelResult(ecapa));
            root.set("SINGER_BYOL", modelResult(singerByol));
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private ObjectNode modelResult(VoiceModelResult result) throws JacksonException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelVersion", result.getModelVersion());
        node.put("dimension", result.getDimension());
        node.set("embedding", objectMapper.readTree(result.getVectorJson()));
        node.set("segmentInfo", objectMapper.readTree(result.getSegmentInfo()));
        return node;
    }

    private boolean valid(VoiceModelResult result, int dimension) {
        return result != null && result.getDimension() == dimension
                && result.getVectorJson() != null && !result.getVectorJson().isBlank()
                && result.getSegmentInfo() != null && !result.getSegmentInfo().isBlank();
    }
}
