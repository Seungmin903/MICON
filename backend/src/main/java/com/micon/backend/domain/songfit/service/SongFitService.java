package com.micon.backend.domain.songfit.service;

import com.micon.backend.domain.songfit.client.SongFitGateway;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository;
import com.micon.backend.domain.songfit.api.SongFitResponse;
import com.micon.backend.domain.songfit.api.SongFitResponse.BurdenSection;
import com.micon.backend.domain.songfit.api.SongFitResponse.RangeFit;
import com.micon.backend.domain.songfit.api.SongFitResponse.RecommendationScores;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
@Transactional(readOnly = true)
public class SongFitService {

	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
	private static final int MIN_RECOMMENDED_KEY_OFFSET = -6;
	private static final int MAX_RECOMMENDED_KEY_OFFSET = 5;

	private final SongFitQueryRepository songFitQueryRepository;
	private final SongFitGateway songFitGateway;
	private final boolean aiEnabled;

	public SongFitService(
		SongFitQueryRepository songFitQueryRepository,
		SongFitGateway songFitGateway,
		@Value("${ai.song-fit-enabled:false}") boolean aiEnabled
	) {
		this.songFitQueryRepository = songFitQueryRepository;
		this.songFitGateway = songFitGateway;
		this.aiEnabled = aiEnabled;
	}

	public SongFitResponse getSongFit(Long userId, String songId, Long analysisId, int keyOffset) {
		if (!songFitQueryRepository.songExists(songId)) {
			throw new SongNotFoundException(songId);
		}

		SongFitQueryRepository.UserRangeRow userRange = songFitQueryRepository.findUserRange(userId, analysisId)
			.orElseThrow(() -> new VoiceAnalysisNotFoundException(analysisId));
		if (!"SAVED".equals(userRange.sessionStatus())
			|| !"AVAILABLE".equals(userRange.measurementStatus())
			|| !isValidRange(userRange.stableLowMidi(), userRange.stableHighMidi())) {
			return SongFitResponse.unavailable(songId, analysisId, keyOffset, "USER_STABLE_RANGE_UNAVAILABLE");
		}

		SongFitQueryRepository.SongRangeRow songRange = songFitQueryRepository.findLatestSongRange(songId).orElse(null);
		if (songRange == null || !isValidRange(songRange.lowestMidi(), songRange.highestMidi())) {
			return SongFitResponse.unavailable(songId, analysisId, keyOffset, "SONG_RANGE_UNAVAILABLE");
		}

		SongFitQueryRepository.RecommendationRow recommendation = songFitQueryRepository
			.findRecommendation(userId, analysisId, songId)
			.orElse(null);
		RecommendationScores scores = recommendation == null ? null : new RecommendationScores(
			recommendation.overallScore(),
			recommendation.rangeFitScore(),
			recommendation.timbreFitScore(),
			recommendation.rhythmFitScore(),
			recommendation.algorithmVersion(),
			recommendation.timbreAxis(),
			recommendation.timbreLabel()
		);

		List<BurdenSection> burdenSections = songFitQueryRepository.findBurdenSections(songId, songRange.analysisId());
		if (aiEnabled) {
			try {
				SongFitResponse response = songFitGateway.evaluate(new SongFitGateway.Command(
					userId, songId, analysisId, songRange.analysisId(), keyOffset,
					userRange.stableLowMidi(), userRange.stableHighMidi(),
					songRange.lowestMidi(), songRange.highestMidi(),
					recommendation == null ? null : recommendation.recommendedKey(),
					scores, recommendation == null ? null : recommendation.reasonText(), burdenSections
				));
				if (response == null || !songId.equals(response.songId()) || !analysisId.equals(response.analysisId())
					|| response.selectedKeyOffset() != keyOffset) {
					throw new SongFitEvaluationFailedException();
				}
				Integer recommendedKeyOffset = response.recommendedKeyOffset();
				if (recommendedKeyOffset != null
					&& (recommendedKeyOffset < MIN_RECOMMENDED_KEY_OFFSET
						|| recommendedKeyOffset > MAX_RECOMMENDED_KEY_OFFSET)) {
					throw new SongFitEvaluationFailedException();
				}
				return response;
			} catch (SongFitEvaluationFailedException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				throw new SongFitEvaluationFailedException();
			}
		}

		return new SongFitResponse(
			songId,
			analysisId,
			keyOffset,
			"AVAILABLE",
			calculateRangeFit(userRange, songRange, 0),
			calculateRangeFit(userRange, songRange, keyOffset),
			recommendation == null ? null : recommendation.recommendedKey(),
			scores,
			recommendation == null ? null : recommendation.reasonText(),
			burdenSections,
			null
		);
	}

	private RangeFit calculateRangeFit(SongFitQueryRepository.UserRangeRow userRange, SongFitQueryRepository.SongRangeRow songRange, int keyOffset) {
		BigDecimal offset = BigDecimal.valueOf(keyOffset);
		BigDecimal shiftedLow = songRange.lowestMidi().add(offset);
		BigDecimal shiftedHigh = songRange.highestMidi().add(offset);
		BigDecimal songWidth = shiftedHigh.subtract(shiftedLow);
		BigDecimal overlapLow = shiftedLow.max(userRange.stableLowMidi());
		BigDecimal overlapHigh = shiftedHigh.min(userRange.stableHighMidi());
		BigDecimal overlap = overlapHigh.subtract(overlapLow).max(BigDecimal.ZERO);

		BigDecimal score;
		if (songWidth.signum() == 0) {
			score = shiftedLow.compareTo(userRange.stableLowMidi()) >= 0
				&& shiftedLow.compareTo(userRange.stableHighMidi()) <= 0
				? ONE_HUNDRED : BigDecimal.ZERO;
		} else {
			score = overlap.multiply(ONE_HUNDRED)
				.divide(songWidth, 2, RoundingMode.HALF_UP)
				.min(ONE_HUNDRED);
		}

		return new RangeFit(
			userRange.stableLowMidi(),
			userRange.stableHighMidi(),
			shiftedLow,
			shiftedHigh,
			score.setScale(2, RoundingMode.HALF_UP)
		);
	}

	private boolean isValidRange(BigDecimal low, BigDecimal high) {
		return low != null && high != null && low.compareTo(high) <= 0;
	}
}
