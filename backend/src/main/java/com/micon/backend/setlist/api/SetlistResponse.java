package com.micon.backend.setlist.api;

import java.time.LocalDateTime;
import java.util.List;

/** 세트리스트 목록·상세와 포함된 곡 항목의 응답 DTO를 모아 둔 컨테이너다. */
public final class SetlistResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private SetlistResponse() {
	}

	public record Summary(
		Long setlistId,
		String name,
		int songCount,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {
	}

	public record Detail(
		Long setlistId,
		String name,
		List<SongItem> songs,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {
	}

	public record SongItem(
		Long songId,
		String title,
		List<String> artists,
		String imageUrl,
		Long durationMs,
		int position
	) {
	}
}
