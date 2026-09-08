package com.micon.backend.recommendation.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class VoiceAnalysisRequiredException extends BusinessException {

	public VoiceAnalysisRequiredException() {
		super(ErrorCode.VOICE_ANALYSIS_NOT_FOUND, "완료된 음성 분석 결과를 찾을 수 없습니다.");
	}
}
