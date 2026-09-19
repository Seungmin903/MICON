package com.micon.backend.domain.homepage.dto;

/** 곡별 연습 데이터 구성요소와 최종 연습 가능 여부를 나타낸다. */
public record HomepageSongStatus(
	String songId,
	boolean storedPracticeReady,
	boolean practiceReady,
	boolean lyricsPlainReady,
	boolean lyricsSyncedReady,
	boolean analysisReady,
	boolean targetNotesReady,
	boolean originalInstrumentalReady
) {

	public static HomepageSongStatus missing(String songId) {
		return new HomepageSongStatus(songId, false, false, false, false, false, false, false);
	}
}
