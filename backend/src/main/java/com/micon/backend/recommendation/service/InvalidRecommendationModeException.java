package com.micon.backend.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class InvalidRecommendationModeException extends BusinessException {

	public InvalidRecommendationModeException(String mode) {
		super(ErrorCode.INVALID_REQUEST, "지원하지 않는 추천 모드입니다. mode=" + mode);
	}
}
