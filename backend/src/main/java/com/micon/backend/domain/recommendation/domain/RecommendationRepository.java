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

    @Query("""
            select r from Recommendation r
            where r.user.id = :userId
              and r.profileVersion = r.voiceProfile.profileVersion
              and r.sourceSessionId = r.voiceProfile.session.id
            order by r.generatedAt desc, r.id desc
            """)
    List<Recommendation> findCurrentByUserId(@Param("userId") Long userId,
                                            org.springframework.data.domain.Pageable pageable);

    boolean existsByVoiceProfileIdAndStatus(Long profileId, RecommendationStatus status);

    boolean existsByVoiceProfileIdAndProfileVersionAndSourceSessionIdAndStatus(
            Long profileId, Long profileVersion, Long sourceSessionId, RecommendationStatus status);

    List<Recommendation> findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.id = :id")
    Optional<Recommendation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.jobId = :jobId")
    Optional<Recommendation> findByJobIdForUpdate(@Param("jobId") String jobId);
}
