package com.micon.backend.voice.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserVoiceProfileRepository extends JpaRepository<UserVoiceProfile, Long> {
    Optional<UserVoiceProfile> findByUserId(Long userId);
    Optional<UserVoiceProfile> findByIdAndUserId(Long id, Long userId);
}
