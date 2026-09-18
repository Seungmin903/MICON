package com.micon.backend.domain.songfit.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class SongFitEvaluationFailedException extends BusinessException {

	public SongFitEvaluationFailedException() {
		super(ErrorCode.SONG_FIT_EVALUATION_FAILED);
	}
}
