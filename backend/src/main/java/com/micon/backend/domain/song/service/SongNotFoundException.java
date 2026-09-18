package com.micon.backend.domain.song.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

/** 요청한 곡이 카탈로그에 없을 때 SONG_NOT_FOUND 응답으로 변환되는 도메인 예외다. */
public class SongNotFoundException extends BusinessException {

	// 기능: 조회에 실패한 곡 ID를 포함해 SONG_NOT_FOUND 예외를 생성한다.
	public SongNotFoundException(String songId) {
		super(ErrorCode.SONG_NOT_FOUND, "곡을 찾을 수 없습니다. songId=" + songId);
	}
}
