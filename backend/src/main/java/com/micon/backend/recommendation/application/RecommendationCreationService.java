package com.micon.backend.recommendation.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.recommendation.domain.RecommendationMode;
import com.micon.backend.voice.domain.VoiceAnalysis;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.domain.VoiceAnalysisStatus;
import org.springframework.stereotype.Service;

@Service
public class RecommendationCreationService {
    private final VoiceAnalysisRepository analyses;
    private final RecommendationDispatchService dispatchService;

    public RecommendationCreationService(VoiceAnalysisRepository analyses,
                                         RecommendationDispatchService dispatchService) {
        this.analyses = analyses;
        this.dispatchService = dispatchService;
    }

    public RecommendationDispatchResult createFromLatestAnalysis(long userId) {
        VoiceAnalysis analysis = analyses.findFirstByUserIdAndStatusOrderByCompletedAtDescIdDesc(
                        userId, VoiceAnalysisStatus.COMPLETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_INPUT_NOT_READY));
        return dispatchService.dispatch(userId, analysis.getId(), RecommendationMode.OVERALL);
    }
}
