package com.micon.backend.song.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 곡 목록과 상세 조회에서 반환하는 응답 DTO를 모아 둔 컨테이너다. */
public final class SongResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private SongResponse() {
	}

	public record Summary(
		String songId,
		String title,
		List<String> artists,
		String imageUrl,
		Long durationMs
	) {
	}

	public record Detail(
		String songId,
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
