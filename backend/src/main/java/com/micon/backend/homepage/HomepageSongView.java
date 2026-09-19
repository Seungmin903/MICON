package com.micon.backend.homepage;

import com.micon.backend.domain.song.api.SongResponse;
import java.util.List;

/** Thymeleaf 곡 목록 화면에 표시할 값만 가공한 뷰 모델이다. */
public record HomepageSongView(
	String songId,
	String title,
	String artists,
	String genres,
	String imageUrl,
	String duration,
	boolean storedPracticeReady,
	boolean practiceReady,
	boolean lyricsPlainReady,
	boolean lyricsSyncedReady,
	boolean analysisReady,
	boolean targetNotesReady,
	boolean originalInstrumentalReady
) {

	/** API 곡 요약 응답을 사람이 읽기 좋은 화면 표시 값으로 변환한다. */
	public static HomepageSongView from(
		SongResponse.Summary song,
		HomepageSongStatus status,
		List<String> genres
	) {
		return new HomepageSongView(
			song.songId(),
			song.title(),
			String.join(", ", song.artists()),
			String.join(", ", genres),
			song.imageUrl(),
			formatDuration(song.durationMs()),
			status.storedPracticeReady(),
			status.practiceReady(),
			status.lyricsPlainReady(),
			status.lyricsSyncedReady(),
			status.analysisReady(),
			status.targetNotesReady(),
			status.originalInstrumentalReady()
		);
	}

	private static String formatDuration(Long durationMs) {
		if (durationMs == null || durationMs < 0) {
			return "-";
		}
		long totalSeconds = durationMs / 1_000;
		return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
	}
}
