package com.micon.backend.songfit.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class SongNotFoundException extends BusinessException {

	public SongNotFoundException(Long songId) {
		super(ErrorCode.SONG_NOT_FOUND, "곡을 찾을 수 없습니다. songId=" + songId);
	}
}
