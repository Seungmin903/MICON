package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.entity.RecommendationMode;
import com.micon.backend.domain.voice.entity.UserVoiceProfile;
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
