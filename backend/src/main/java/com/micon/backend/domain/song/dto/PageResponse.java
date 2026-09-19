package com.micon.backend.domain.song.dto;

import java.util.List;

/** 목록 데이터와 현재 페이지 및 전체 건수를 함께 반환하는 공통 페이지 응답이다. */
public record PageResponse<T>(
	List<T> content,
	int page,
	int size,
	long totalElements,
	int totalPages
) {
	// 기능: 목록과 전체 건수로 페이지 응답을 생성한다.
	public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
		int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
		return new PageResponse<>(content, page, size, totalElements, totalPages);
	}
}
