package com.micon.backend.recommendation.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findByIdAndUserId(Long id, Long userId);

    Optional<Recommendation> findFirstByUserIdOrderByGeneratedAtDescIdDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.jobId = :jobId")
    Optional<Recommendation> findByJobIdForUpdate(@Param("jobId") String jobId);
}
