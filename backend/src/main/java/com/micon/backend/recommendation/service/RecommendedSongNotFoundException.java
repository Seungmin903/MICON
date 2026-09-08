package com.micon.backend.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class RecommendedSongNotFoundException extends BusinessException {

	public RecommendedSongNotFoundException(Long recommendationId, Long songId) {
		super(
			ErrorCode.RECOMMENDED_SONG_NOT_FOUND,
			"추천 결과에 곡이 없습니다. recommendationId=" + recommendationId + ", songId=" + songId
		);
	}
}
