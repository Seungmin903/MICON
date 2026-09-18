package com.micon.backend.domain.recommendation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationShelfRepository extends JpaRepository<RecommendationShelf, Long> {
    List<RecommendationShelf> findByRecommendationIdOrderByOrder(Long recommendationId);
    void deleteByRecommendationId(Long recommendationId);
}
