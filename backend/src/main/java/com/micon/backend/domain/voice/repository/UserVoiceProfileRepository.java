package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.UserVoiceProfile;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserVoiceProfileRepository extends JpaRepository<UserVoiceProfile, Long> {
    Optional<UserVoiceProfile> findByUserId(Long userId);
    Optional<UserVoiceProfile> findByIdAndUserId(Long id, Long userId);
    @Query(value = """
            SELECT p.* FROM user_voice_profile p
            LEFT JOIN user_setting setting_value ON setting_value.user_id = p.user_id
            WHERE p.profile_id > :afterId
              AND NOT EXISTS (
                  SELECT 1 FROM recommendation r
                  WHERE r.user_id = p.user_id
                    AND r.profile_id = p.profile_id
                    AND r.profile_version = p.profile_version
                    AND r.source_session_id = p.session_id
                    AND r.preference_version = COALESCE(setting_value.preference_version, 0)
              )
            ORDER BY p.profile_id
            """, nativeQuery = true)
    List<UserVoiceProfile> findMissingCurrentRecommendationsAfterId(@Param("afterId") long afterId,
                                                                     Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserVoiceProfile p where p.id = :id and p.user.id = :userId")
    Optional<UserVoiceProfile> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
