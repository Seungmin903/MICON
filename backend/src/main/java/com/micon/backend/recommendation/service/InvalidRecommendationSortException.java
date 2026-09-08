package com.micon.backend.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class InvalidRecommendationSortException extends BusinessException {

	public InvalidRecommendationSortException(String sort) {
		super(ErrorCode.INVALID_REQUEST, "지원하지 않는 추천 정렬 조건입니다. sort=" + sort);
	}
}
