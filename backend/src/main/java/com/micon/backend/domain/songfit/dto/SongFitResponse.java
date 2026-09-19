package com.micon.backend.domain.songfit.dto;

import java.math.BigDecimal;
import java.util.List;

public record SongFitResponse(
	String songId,
	Long analysisId,
	int selectedKeyOffset,
	String evaluationStatus,
	RangeFit originalKeyFit,
	RangeFit selectedKeyFit,
	Integer recommendedKeyOffset,
	RecommendationScores recommendationScores,
	String reason,
	List<BurdenSection> burdenSections,
	String unavailableReason
) {

	public static SongFitResponse unavailable(
		String songId,
		Long analysisId,
		int selectedKeyOffset,
		String reason
	) {
		return new SongFitResponse(
			songId, analysisId, selectedKeyOffset, "UNAVAILABLE", null, null,
			null, null, null, List.of(), reason
		);
	}

	public record RangeFit(
		BigDecimal userStableLowMidi,
		BigDecimal userStableHighMidi,
		BigDecimal songLowMidi,
		BigDecimal songHighMidi,
		BigDecimal score
	) {
	}

	public record RecommendationScores(
		BigDecimal overall,
		BigDecimal range,
		BigDecimal timbre,
		BigDecimal rhythm,
		String algorithmVersion,
		String timbreAxis,
		String timbreLabel
	) {
		public RecommendationScores(BigDecimal overall, BigDecimal range, BigDecimal timbre,
			BigDecimal rhythm, String algorithmVersion) {
			this(overall, range, timbre, rhythm, algorithmVersion, null, null);
		}
	}

	public record BurdenSection(
		Long sectionId,
		String sectionType,
		String sectionName,
		int startMs,
		int endMs,
		String difficultyType,
		BigDecimal difficultyScore,
		String description
	) {
	}
}
