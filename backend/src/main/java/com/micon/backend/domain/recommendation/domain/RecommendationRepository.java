package com.micon.backend.domain.recommendation.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findByIdAndUserId(Long id, Long userId);

    Optional<Recommendation> findFirstByUserIdOrderByGeneratedAtDescIdDesc(Long userId);

    boolean existsByVoiceProfileIdAndStatus(Long profileId, RecommendationStatus status);

    List<Recommendation> findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.id = :id")
    Optional<Recommendation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.jobId = :jobId")
    Optional<Recommendation> findByJobIdForUpdate(@Param("jobId") String jobId);
}
