package com.micon.backend.domain.practice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 연습용 미디어·가사·목표 음정·구간·난관 데이터를 반환하는 응답 DTO를 모아 둔다. */
public final class PracticeAssetResponse {

	// 기능: DTO 타입만 사용하도록 외부 객체 생성을 막는다.
	private PracticeAssetResponse() {
	}

	public record Detail(
		String songId,
		String title,
		int selectedKey,
		Media instrumental,
		String lyricsPlain,
		String lyricsSynced,
		JsonNode targetNotes,
		String dataVersion,
		List<Section> sections,
		String keyName,
		BigDecimal tempoBpm,
		String timeSignature,
		JsonNode beatGrid
	) {
	}

	public record Media(
		Long mediaId,
		String filePath,
		String mimeType,
		Long fileBytes,
		String sha256,
		String playbackUrl,
		Instant playbackUrlExpiresAt
	) {
	}

	public record Instrumental(
		String songId,
		int selectedKey,
		Media instrumental,
		String lyricsSynced
	) {
	}

	public record Section(
		Long sectionId,
		String sectionType,
		String sectionName,
		int startMs,
		int endMs,
		int sequenceNo,
		String difficultyType,
		BigDecimal difficultyScore,
		BigDecimal confidence,
		String description
	) {
	}

	public record Guide(
		String songId,
		int selectedKey,
		String dataVersion,
		List<Section> difficultySections
	) {
	}
}
