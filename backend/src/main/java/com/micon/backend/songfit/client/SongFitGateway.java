package com.micon.backend.songfit.client;

import com.micon.backend.songfit.api.SongFitResponse;
import com.micon.backend.songfit.api.SongFitResponse.BurdenSection;
import com.micon.backend.songfit.api.SongFitResponse.RecommendationScores;
import java.math.BigDecimal;
import java.util.List;

public interface SongFitGateway {

	SongFitResponse evaluate(Command command);

	record Command(
		Long userId,
		Long songId,
		Long analysisId,
		Long songAnalysisId,
		int selectedKeyOffset,
		BigDecimal userStableLowMidi,
		BigDecimal userStableHighMidi,
		BigDecimal songLowMidi,
		BigDecimal songHighMidi,
		Integer recommendedKeyOffset,
		RecommendationScores recommendationScores,
		String reason,
		List<BurdenSection> burdenSections
	) {
	}
}
