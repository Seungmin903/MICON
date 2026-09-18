package com.micon.backend.domain.setlist.exception;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

/** 세트리스트가 없거나 현재 회원의 소유가 아닐 때 사용하는 비즈니스 예외다. */
public class SetlistNotFoundException extends BusinessException {

	// 기능: 식별자를 포함한 비즈니스 예외를 생성한다.
	public SetlistNotFoundException(Long setlistId) {
		super(ErrorCode.SETLIST_NOT_FOUND, "세트리스트를 찾을 수 없습니다. setlistId=" + setlistId);
	}
}
