package com.micon.backend.setlist.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;

public class SetlistNotFoundException extends BusinessException {

	public SetlistNotFoundException(Long setlistId) {
		super(ErrorCode.SETLIST_NOT_FOUND, "세트리스트를 찾을 수 없습니다. setlistId=" + setlistId);
	}
}
