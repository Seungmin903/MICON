package com.micon.backend.song.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SongResponse {

	private SongResponse() {
	}

	public record Summary(
		Long songId,
		String title,
		List<String> artists,
		String imageUrl,
		Long durationMs
	) {
	}

	public record Detail(
		Long songId,
		String title,
		List<String> artists,
		String albumName,
		String imageUrl,
		LocalDate releaseDate,
		Long durationMs,
		String originalKey,
		BigDecimal bpm,
		List<String> genres,
		BigDecimal lowestMidi,
		BigDecimal highestMidi,
		boolean favorite,
		boolean practiceReady
	) {
	}
}
