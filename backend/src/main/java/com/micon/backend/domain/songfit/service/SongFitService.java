package com.micon.backend.domain.songfit.service;

import com.micon.backend.domain.songfit.client.SongFitGateway;
import com.micon.backend.domain.songfit.repository.SongFitQueryRepository;
import com.micon.backend.domain.songfit.dto.SongFitResponse;
import com.micon.backend.domain.songfit.dto.SongFitResponse.BurdenSection;
import com.micon.backend.domain.songfit.dto.SongFitResponse.RangeFit;
import com.micon.backend.domain.songfit.dto.SongFitResponse.RecommendationScores;

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
	private static final BigDecimal COMFORT_PADDING = BigDecimal.valueOf(2);
	private static final BigDecimal TARGET_COVERAGE_PERCENT = new BigDecimal("98.00");
	private static final int MIN_RECOMMENDED_KEY_OFFSET = -3;
	private static final int MAX_RECOMMENDED_KEY_OFFSET = 3;

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
		Integer storedRecommendedKey = supportedKey(recommendation == null ? null : recommendation.recommendedKey());

		List<BurdenSection> burdenSections = songFitQueryRepository.findBurdenSections(songId, songRange.analysisId());
		if (aiEnabled) {
			try {
				SongFitResponse response = songFitGateway.evaluate(new SongFitGateway.Command(
					userId, songId, analysisId, songRange.analysisId(), keyOffset,
					userRange.stableLowMidi(), userRange.stableHighMidi(),
					songRange.lowestMidi(), songRange.highestMidi(),
					storedRecommendedKey,
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

		Integer recommendedKey = storedRecommendedKey == null
			? recommendKey(userRange, songRange)
			: storedRecommendedKey;
		String reason = recommendation != null && storedRecommendedKey != null
			? recommendation.reasonText()
			: keyRecommendationReason(recommendedKey);
		return new SongFitResponse(
			songId,
			analysisId,
			keyOffset,
			"AVAILABLE",
			calculateRangeFit(userRange, songRange, 0),
			calculateRangeFit(userRange, songRange, keyOffset),
			recommendedKey,
			scores,
			reason,
			burdenSections,
			null
		);
	}

	private RangeFit calculateRangeFit(SongFitQueryRepository.UserRangeRow userRange, SongFitQueryRepository.SongRangeRow songRange, int keyOffset) {
		BigDecimal offset = BigDecimal.valueOf(keyOffset);
		BigDecimal shiftedLow = songRange.lowestMidi().add(offset);
		BigDecimal shiftedHigh = songRange.highestMidi().add(offset);
		BigDecimal songWidth = shiftedHigh.subtract(shiftedLow);
		BigDecimal observedLow = userRange.stableLowMidi().subtract(COMFORT_PADDING);
		BigDecimal observedHigh = userRange.stableHighMidi().add(COMFORT_PADDING);
		BigDecimal overlapLow = shiftedLow.max(observedLow);
		BigDecimal overlapHigh = shiftedHigh.min(observedHigh);
		BigDecimal overlap = overlapHigh.subtract(overlapLow).max(BigDecimal.ZERO);

		BigDecimal score;
		if (songWidth.signum() == 0) {
			score = shiftedLow.compareTo(observedLow) >= 0
				&& shiftedLow.compareTo(observedHigh) <= 0
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

	private Integer recommendKey(SongFitQueryRepository.UserRangeRow userRange,
		SongFitQueryRepository.SongRangeRow songRange) {
		BigDecimal[] scores = new BigDecimal[MAX_RECOMMENDED_KEY_OFFSET - MIN_RECOMMENDED_KEY_OFFSET + 1];
		for (int key = MIN_RECOMMENDED_KEY_OFFSET; key <= MAX_RECOMMENDED_KEY_OFFSET; key++) {
			BigDecimal score = calculateRangeFit(userRange, songRange, key).score();
			scores[key - MIN_RECOMMENDED_KEY_OFFSET] = score;
		}

		Integer bestKey = null;
		BigDecimal bestScore = BigDecimal.valueOf(-1);
		for (int key = MIN_RECOMMENDED_KEY_OFFSET; key <= MAX_RECOMMENDED_KEY_OFFSET; key++) {
			BigDecimal score = scores[key - MIN_RECOMMENDED_KEY_OFFSET];
			if (score.compareTo(TARGET_COVERAGE_PERCENT) < 0) continue;
			if (bestKey == null || Math.abs(key) < Math.abs(bestKey)
				|| (Math.abs(key) == Math.abs(bestKey) && score.compareTo(bestScore) > 0)
				|| (Math.abs(key) == Math.abs(bestKey) && score.compareTo(bestScore) == 0 && key < bestKey)) {
				bestKey = key;
				bestScore = score;
			}
		}
		if (bestKey != null) return bestKey;

		BigDecimal bestExcess = null;
		bestScore = BigDecimal.valueOf(-1);
		for (int key = MIN_RECOMMENDED_KEY_OFFSET; key <= MAX_RECOMMENDED_KEY_OFFSET; key++) {
			BigDecimal score = scores[key - MIN_RECOMMENDED_KEY_OFFSET];
			BigDecimal excess = coreExcess(userRange, songRange, key);
			if (bestKey == null || excess.compareTo(bestExcess) < 0
				|| (excess.compareTo(bestExcess) == 0 && score.compareTo(bestScore) > 0)
				|| (excess.compareTo(bestExcess) == 0 && score.compareTo(bestScore) == 0
					&& Math.abs(key) < Math.abs(bestKey))
				|| (excess.compareTo(bestExcess) == 0 && score.compareTo(bestScore) == 0
					&& Math.abs(key) == Math.abs(bestKey) && key < bestKey)) {
				bestKey = key;
				bestScore = score;
				bestExcess = excess;
			}
		}
		return bestKey == null ? 0 : bestKey;
	}

	private BigDecimal coreExcess(SongFitQueryRepository.UserRangeRow userRange,
		SongFitQueryRepository.SongRangeRow songRange, int key) {
		BigDecimal shiftedLow = songRange.lowestMidi().add(BigDecimal.valueOf(key));
		BigDecimal shiftedHigh = songRange.highestMidi().add(BigDecimal.valueOf(key));
		BigDecimal observedLow = userRange.stableLowMidi().subtract(COMFORT_PADDING);
		BigDecimal observedHigh = userRange.stableHighMidi().add(COMFORT_PADDING);
		return observedLow.subtract(shiftedLow).max(BigDecimal.ZERO)
			.add(shiftedHigh.subtract(observedHigh).max(BigDecimal.ZERO));
	}

	private Integer supportedKey(Integer key) {
		return key != null && key >= MIN_RECOMMENDED_KEY_OFFSET && key <= MAX_RECOMMENDED_KEY_OFFSET ? key : null;
	}

	private String keyRecommendationReason(int key) {
		if (key == 0) return "안정 음역과 비교했을 때 원키가 가장 잘 맞습니다.";
		String count = switch (Math.abs(key)) {
			case 1 -> "한";
			case 2 -> "두";
			case 3 -> "세";
			default -> Integer.toString(Math.abs(key));
		};
		return count + " 키 " + (key < 0 ? "내리기" : "올리기") + "를 추천합니다.";
	}

	private boolean isValidRange(BigDecimal low, BigDecimal high) {
		return low != null && high != null && low.compareTo(high) <= 0;
	}
}
