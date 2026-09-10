package com.micon.backend.practice.api;

import java.math.BigDecimal;
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
		JsonNode lyricsSynced,
		JsonNode targetNotes,
		String dataVersion,
		List<Section> sections
	) {
	}

	public record Media(
		Long mediaId,
		String filePath,
		String mimeType,
		Long fileBytes,
		String sha256
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
