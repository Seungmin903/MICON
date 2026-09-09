package com.micon.backend.setlist.api;

import java.time.LocalDateTime;
import java.util.List;

public final class SetlistResponse {

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
