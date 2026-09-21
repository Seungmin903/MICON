package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import com.micon.backend.domain.voice.repository.VoiceModelResultRepository;
import com.micon.backend.domain.voice.repository.VoiceRangeMeasurementRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
import com.micon.backend.domain.voice.entity.VoiceModelResult;
import com.micon.backend.domain.voice.entity.VoiceRangeMeasurement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RecommendationInputAssembler {

    private final UserVoiceProfileRepository profiles;
    private final VoiceRangeMeasurementRepository rangeResults;
    private final VoiceModelResultRepository modelResults;
    private final RecommendationPreferenceReader preferences;
    private final ObjectMapper json;

    public RecommendationInputAssembler(UserVoiceProfileRepository profiles,
                                        VoiceRangeMeasurementRepository rangeResults,
                                        VoiceModelResultRepository modelResults,
                                        RecommendationPreferenceReader preferences, ObjectMapper json) {
        this.profiles = profiles;
        this.rangeResults = rangeResults;
        this.modelResults = modelResults;
        this.preferences = preferences;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public RecommendationInput assemble(long userId, long profileId) {
        UserVoiceProfile savedProfile = profiles.findByIdAndUserId(profileId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        ensureAvailable(savedProfile);

        long sessionId = savedProfile.getSession().getId();
        VoiceRangeMeasurement range = rangeResults.findBySessionId(sessionId).orElse(null);
        Map<String, Object> embeddings = new LinkedHashMap<>();
        embeddings.put("ECAPA", model(sessionId, "ECAPA", 192));
        embeddings.put("SINGER_BYOL", model(sessionId, "SINGER_BYOL", 1000));
        RecommendationInput.VoiceProfile profile = profile(range, json.writeValueAsString(embeddings));
        RecommendationPreferenceReader.Preferences preference = preferences.findByUserId(userId);
        if (preference == null) {
            preference = RecommendationPreferenceReader.Preferences.empty();
        }

        return new RecommendationInput(
                savedProfile.getId(),
                sessionId,
                savedProfile.getProfileVersion(),
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

    private Map<String, Object> model(long sessionId, String key, int dimension) {
        VoiceModelResult result = modelResults.findBySessionIdAndModelKey(sessionId, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
        try {
            JsonNode vector = json.readTree(result.getVectorJson());
            JsonNode segments = json.readTree(result.getSegmentInfo());
            if (!key.equals(result.getModelKey()) || result.getDimension() != dimension
                    || result.getModelVersion() == null || result.getModelVersion().isBlank()
                    || vector == null || !vector.isArray() || vector.size() != dimension
                    || segments == null || !segments.isObject()) throw new IllegalArgumentException();
            double energy = 0;
            for (JsonNode value : vector) {
                if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException();
                energy += value.asDouble() * value.asDouble();
            }
            if (!Double.isFinite(energy) || energy < 1e-12) throw new IllegalArgumentException();
            return Map.of("modelVersion", result.getModelVersion(), "dimension", dimension,
                    "embedding", vector, "segmentInfo", segments);
        } catch (RuntimeException invalid) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY,
                    "저장된 " + key + " 임베딩이 추천 입력 계약과 일치하지 않습니다.");
        }
    }

    private RecommendationInput.VoiceProfile profile(VoiceRangeMeasurement range, String embeddings) {
        boolean free = range != null && "guided-range-v8".equals(range.getRuleVersion());
        return new RecommendationInput.VoiceProfile(
                range == null ? null : free ? range.getStableLowMidi() : range.getFullLowMidi(),
                range == null ? null : free ? range.getStableHighMidi() : range.getFullHighMidi(),
                null,
                range == null ? null : range.getNoteResults(),
                null,
                null, null, null, embeddings,
                null,
                null, null, null, null);
    }
}
