package com.micon.backend.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class RecommendationNotFoundException extends BusinessException {

	public RecommendationNotFoundException(Long recommendationId) {
		super(
			ErrorCode.RECOMMENDATION_NOT_FOUND,
			recommendationId == null
				? "추천 결과를 찾을 수 없습니다."
				: "추천 결과를 찾을 수 없습니다. recommendationId=" + recommendationId
		);
	}
}
