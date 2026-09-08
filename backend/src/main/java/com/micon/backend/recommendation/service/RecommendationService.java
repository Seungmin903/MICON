package com.micon.backend.recommendation.service;

import com.micon.backend.recommendation.api.RecommendationResponse;
import com.micon.backend.recommendation.repository.RecommendationQueryRepository;
import com.micon.backend.recommendation.repository.RecommendationQueryRepository.RecommendationRow;
import com.micon.backend.recommendation.repository.RecommendationQueryRepository.RecommendedSongRow;
import com.micon.backend.recommendation.repository.RecommendationQueryRepository.SongSort;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RecommendationService {

	private static final List<String> MODES = List.of("OVERALL", "RANGE", "TIMBRE", "GENRE", "POPULAR");

	private final RecommendationQueryRepository recommendationQueryRepository;

	public RecommendationService(RecommendationQueryRepository recommendationQueryRepository) {
		this.recommendationQueryRepository = recommendationQueryRepository;
	}

	public RecommendationResponse.Detail getLatest(Long userId, String mode, String sort) {
		String normalizedMode = normalizeMode(mode);
		RecommendationRow recommendation = recommendationQueryRepository.findLatest(userId, normalizedMode)
			.orElseThrow(() -> {
				if (!recommendationQueryRepository.hasCompletedVoiceAnalysis(userId)) {
					return new VoiceAnalysisRequiredException();
				}
				return new RecommendationNotFoundException(null);
			});
		return toDetail(recommendation, normalizeSort(sort));
	}

	public RecommendationResponse.Detail get(Long userId, Long recommendationId, String sort) {
		return toDetail(findOwnedRecommendation(userId, recommendationId), normalizeSort(sort));
	}

	public RecommendationResponse.SongDetail getSong(Long userId, Long recommendationId, Long songId) {
		findOwnedRecommendation(userId, recommendationId);
		RecommendedSongRow song = recommendationQueryRepository.findSong(recommendationId, songId)
			.orElseThrow(() -> new RecommendedSongNotFoundException(recommendationId, songId));
		Map<Long, List<String>> artists = recommendationQueryRepository.findArtists(List.of(song.songId()));
		return new RecommendationResponse.SongDetail(
			recommendationId, song.songId(), song.rank(), song.title(),
			artists.getOrDefault(song.songId(), List.of()), song.imageUrl(), song.durationMs(),
			song.recommendedKey(), song.overallScore(), song.rangeFitScore(),
			song.timbreFitScore(), song.rhythmFitScore(), song.reason()
		);
	}

	private RecommendationRow findOwnedRecommendation(Long userId, Long recommendationId) {
		return recommendationQueryRepository.findById(userId, recommendationId)
			.orElseThrow(() -> new RecommendationNotFoundException(recommendationId));
	}

	private RecommendationResponse.Detail toDetail(RecommendationRow recommendation, SongSort sort) {
		List<RecommendedSongRow> rows = recommendationQueryRepository.findSongs(recommendation.recommendationId(), sort);
		Map<Long, List<String>> artists = recommendationQueryRepository.findArtists(
			rows.stream().map(RecommendedSongRow::songId).toList()
		);
		List<RecommendationResponse.SongSummary> songs = rows.stream()
			.map(song -> new RecommendationResponse.SongSummary(
				song.songId(), song.rank(), song.title(), artists.getOrDefault(song.songId(), List.of()),
				song.imageUrl(), song.durationMs(), song.recommendedKey(), song.overallScore(), song.reason()
			))
			.toList();
		return new RecommendationResponse.Detail(
			recommendation.recommendationId(), recommendation.analysisId(), recommendation.mode(),
			recommendation.status(), recommendation.generatedAt(), recommendation.expiresAt(),
			recommendation.algorithmVersion(), songs
		);
	}

	private String normalizeMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return null;
		}
		String normalized = mode.trim().toUpperCase(Locale.ROOT);
		if (!MODES.contains(normalized)) {
			throw new InvalidRecommendationModeException(mode);
		}
		return normalized;
	}

	private SongSort normalizeSort(String sort) {
		if (sort == null || sort.isBlank() || "rank".equalsIgnoreCase(sort)) {
			return SongSort.RANK;
		}
		if ("score".equalsIgnoreCase(sort)) {
			return SongSort.SCORE;
		}
		throw new InvalidRecommendationSortException(sort);
	}
}
