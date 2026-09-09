package com.micon.backend.songfit.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class VoiceAnalysisNotFoundException extends BusinessException {

	public VoiceAnalysisNotFoundException(Long analysisId) {
		super(ErrorCode.VOICE_ANALYSIS_NOT_FOUND, "음성 분석 결과를 찾을 수 없습니다. analysisId=" + analysisId);
	}
}
