package com.micon.backend.domain.recommendation.repository;

import com.micon.backend.domain.recommendation.entity.RecommendationSong;
import com.micon.backend.domain.recommendation.entity.RecommendationSongId;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendationSongRepository extends JpaRepository<RecommendationSong, RecommendationSongId> {
    List<RecommendationSong> findByRecommendationIdOrderByOverallRank(Long recommendationId);
    Optional<RecommendationSong> findFirstByRecommendationIdAndIdSongIdOrderByOverallRank(Long recommendationId, String songId);
    void deleteByRecommendationId(Long recommendationId);
}
