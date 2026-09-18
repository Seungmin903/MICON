package com.micon.backend.domain.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.domain.RecommendationMode;
import com.micon.backend.domain.voice.domain.UserVoiceProfile;
import com.micon.backend.domain.voice.domain.UserVoiceProfileRepository;
import org.springframework.stereotype.Service;

@Service
public class RecommendationCreationService {
    private final UserVoiceProfileRepository profiles;
    private final RecommendationDispatchService dispatchService;

    public RecommendationCreationService(UserVoiceProfileRepository profiles,
                                         RecommendationDispatchService dispatchService) {
        this.profiles = profiles;
        this.dispatchService = dispatchService;
    }

    public RecommendationDispatchResult createFromLatestAnalysis(long userId) {
        UserVoiceProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
        return dispatchService.dispatch(userId, profile.getId(), RecommendationMode.OVERALL);
    }
}
