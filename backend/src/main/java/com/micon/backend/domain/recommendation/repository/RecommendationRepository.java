package com.micon.backend.domain.recommendation.repository;

import com.micon.backend.domain.recommendation.entity.Recommendation;
import com.micon.backend.domain.recommendation.entity.RecommendationStatus;

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

    @Query(value = """
            SELECT r.* FROM recommendation r
            JOIN user_voice_profile profile ON profile.profile_id = r.profile_id
            LEFT JOIN user_setting setting_value ON setting_value.user_id = r.user_id
            WHERE r.user_id = :userId
              AND r.profile_version = profile.profile_version
              AND r.source_session_id = profile.session_id
              AND r.preference_version = COALESCE(setting_value.preference_version, 0)
            ORDER BY r.generated_at DESC, r.recommendation_id DESC
            """, nativeQuery = true)
    List<Recommendation> findCurrentByUserId(@Param("userId") Long userId,
                                            org.springframework.data.domain.Pageable pageable);

    boolean existsByVoiceProfileIdAndStatus(Long profileId, RecommendationStatus status);

    boolean existsByVoiceProfileIdAndProfileVersionAndSourceSessionIdAndPreferenceVersionAndStatus(
            Long profileId, Long profileVersion, Long sourceSessionId, Long preferenceVersion, RecommendationStatus status);

    List<Recommendation> findByStatusAndJobIdIsNotNullOrderByGeneratedAt(RecommendationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.id = :id")
    Optional<Recommendation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recommendation from Recommendation recommendation where recommendation.jobId = :jobId")
    Optional<Recommendation> findByJobIdForUpdate(@Param("jobId") String jobId);
}
