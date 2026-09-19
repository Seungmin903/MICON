package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.UserVoiceProfile;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserVoiceProfileRepository extends JpaRepository<UserVoiceProfile, Long> {
    Optional<UserVoiceProfile> findByUserId(Long userId);
    Optional<UserVoiceProfile> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserVoiceProfile p where p.id = :id and p.user.id = :userId")
    Optional<UserVoiceProfile> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
