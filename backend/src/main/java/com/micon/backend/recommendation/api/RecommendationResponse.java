package com.micon.backend.recommendation.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class RecommendationResponse {

	private RecommendationResponse() {
	}

	public record Detail(
		Long recommendationId,
		Long analysisId,
		String mode,
		String status,
		LocalDateTime generatedAt,
		LocalDateTime expiresAt,
		String algorithmVersion,
		List<SongSummary> songs
	) {
	}

	public record SongSummary(
		Long songId,
		int rank,
		String title,
		List<String> artists,
		String imageUrl,
		Long durationMs,
		Integer recommendedKey,
		BigDecimal overallScore,
		String reason
	) {
	}

	public record SongDetail(
		Long recommendationId,
		Long songId,
		int rank,
		String title,
		List<String> artists,
		String imageUrl,
		Long durationMs,
		Integer recommendedKey,
		BigDecimal overallScore,
		BigDecimal rangeFitScore,
		BigDecimal timbreFitScore,
		BigDecimal rhythmFitScore,
		String reason
	) {
	}
}
