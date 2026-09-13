package com.micon.backend.practice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class PracticeAssetResponse {

	private PracticeAssetResponse() {
	}

	public record Detail(
		Long songId,
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
		Long songId,
		int selectedKey,
		String dataVersion,
		List<Section> difficultySections
	) {
	}
}
