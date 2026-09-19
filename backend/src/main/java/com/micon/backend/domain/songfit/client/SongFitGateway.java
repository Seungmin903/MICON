package com.micon.backend.domain.songfit.client;

import com.micon.backend.domain.songfit.dto.SongFitResponse;
import com.micon.backend.domain.songfit.dto.SongFitResponse.BurdenSection;
import com.micon.backend.domain.songfit.dto.SongFitResponse.RecommendationScores;
import java.math.BigDecimal;
import java.util.List;

public interface SongFitGateway {

	SongFitResponse evaluate(Command command);

	record Command(
		Long userId,
		String songId,
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
