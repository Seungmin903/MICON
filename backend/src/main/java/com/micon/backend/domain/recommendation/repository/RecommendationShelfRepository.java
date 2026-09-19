package com.micon.backend.domain.recommendation.repository;

import com.micon.backend.domain.recommendation.entity.RecommendationShelf;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationShelfRepository extends JpaRepository<RecommendationShelf, Long> {
    List<RecommendationShelf> findByRecommendationIdOrderByOrder(Long recommendationId);
    void deleteByRecommendationId(Long recommendationId);
}
