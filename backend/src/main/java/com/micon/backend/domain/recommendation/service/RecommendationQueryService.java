package com.micon.backend.domain.recommendation.service;

import com.micon.backend.domain.recommendation.repository.RecommendationRepository;
import com.micon.backend.domain.recommendation.repository.RecommendationShelfRepository;
import com.micon.backend.domain.recommendation.repository.RecommendationSongRepository;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.recommendation.dto.RecommendationResponse;
import com.micon.backend.domain.recommendation.entity.Recommendation;
import com.micon.backend.domain.recommendation.entity.RecommendationShelf;
import com.micon.backend.domain.recommendation.entity.RecommendationSong;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;
import com.micon.backend.domain.preference.repository.UserPreferenceRepository;
import com.micon.backend.domain.song.dto.SongResponse;
import com.micon.backend.domain.song.repository.SongQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RecommendationQueryService {
    private final RecommendationRepository recommendations;
    private final RecommendationShelfRepository shelves;
    private final RecommendationSongRepository songs;
    private final ObjectMapper objectMapper;
    private final UserPreferenceRepository preferences;
    private final SongQueryRepository songQueries;

    // Enable after the AI rollout. Old results remain available by ID while latest regenerates them.
    @Value("${ai.recommendation.required-shelves-version:}")
    private String requiredShelvesVersion = "";

    public RecommendationQueryService(RecommendationRepository recommendations,
                                      RecommendationShelfRepository shelves,
                                      RecommendationSongRepository songs,
                                      ObjectMapper objectMapper, UserPreferenceRepository preferences,
                                      SongQueryRepository songQueries) {
        this.recommendations = recommendations;
        this.shelves = shelves;
        this.songs = songs;
        this.objectMapper = objectMapper;
        this.preferences = preferences;
        this.songQueries = songQueries;
    }

    @Transactional(readOnly = true)
    public RecommendationResponse get(long userId, long recommendationId) {
        Recommendation recommendation = recommendations.findByIdAndUserId(recommendationId, userId)
                .filter(Recommendation::isForCurrentProfile)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        return response(recommendation);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse latest(long userId) {
        Recommendation recommendation = recommendations.findCurrentByUserId(userId,
                        org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!requiredShelvesVersion.isBlank()
                && (recommendation.getStatus() == RecommendationStatus.COMPLETED
                    || recommendation.getStatus() == RecommendationStatus.EMPTY)
                && !requiredShelvesVersion.equals(recommendation.getAlgorithmVersion())) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND);
        }
        return response(recommendation);
    }

    @Transactional(readOnly = true)
    public RecommendationResponse.Song song(long userId, long recommendationId, String songId) {
        recommendations.findByIdAndUserId(recommendationId, userId)
                .filter(Recommendation::isForCurrentProfile)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        return songs.findFirstByRecommendationIdAndIdSongIdOrderByOverallRank(recommendationId, songId)
                .map(this::song)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_SONG_NOT_FOUND));
    }

    private RecommendationResponse response(Recommendation recommendation) {
        List<RecommendationShelf> orderedShelves = shelves.findByRecommendationIdOrderByOrder(recommendation.getId());
        Map<Long, List<RecommendationSong>> songsByShelf = songs
                .findByRecommendationIdOrderByOverallRank(recommendation.getId()).stream()
                .collect(Collectors.groupingBy(item -> item.getShelf().getId()));
        Map<String, SongResponse.Summary> summaries = songQueries.findSummariesByIds(
                songsByShelf.values().stream().flatMap(List::stream)
                        .map(item -> item.getId().getSongId()).collect(Collectors.toSet()));
        List<RecommendationResponse.Shelf> shelfResponses = orderedShelves.stream()
                .map(shelf -> new RecommendationResponse.Shelf(
                        shelf.getKey(), shelf.getTitle(), shelf.getMetric(), shelf.getOrder(),
                        songsByShelf.getOrDefault(shelf.getId(), List.of()).stream()
                                .sorted(java.util.Comparator.comparingInt(RecommendationSong::getShelfPosition))
                                .map(item -> song(item, summaries.get(item.getId().getSongId()))).toList()))
                .toList();
        return new RecommendationResponse(
                recommendation.getId(), recommendation.getJobId(), recommendation.getStatus(),
                recommendation.getProgress(), recommendation.getFailureCode(), recommendation.getFailureReason(),
                recommendation.getAlgorithmVersion(), recommendation.getScoringVersion(),
                recommendation.getCompositionVersion(), recommendation.getGeneratedAt(),
                recommendation.getCompletedAt(), recommendation.getExpiresAt(), shelfResponses,
                recommendation.getVoiceProfile().getId(), recommendation.getSourceSessionId(),
                recommendation.getProfileVersion(), recommendation.getPreferenceVersion(),
                preferences.preferenceVersion(recommendation.getUser().getId()));
    }

    private RecommendationResponse.Song song(RecommendationSong song) {
        return song(song, null);
    }

    private RecommendationResponse.Song song(RecommendationSong song, SongResponse.Summary summary) {
        return new RecommendationResponse.Song(
                song.getId().getSongId(), song.getOverallRank(), song.getShelfPosition(), song.getAxis(),
                song.getLabel(), song.getRecommendedKey(), song.getFinalScore(), song.getTimbreScore(),
                json(song.getRangeFit()), json(song.getMetrics()), song.getReasonText(),
                song.getScoringVersion(), song.getCompositionVersion(), summary);
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
