package com.micon.backend.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationSongRepository extends JpaRepository<RecommendationSong, RecommendationSongId> {
    List<RecommendationSong> findByRecommendationIdOrderByOverallRank(Long recommendationId);
    Optional<RecommendationSong> findByRecommendationIdAndIdSongId(Long recommendationId, String songId);
    void deleteByRecommendationId(Long recommendationId);
}
