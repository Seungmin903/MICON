package com.micon.backend.voice.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceProfileSaveJobRepository extends JpaRepository<VoiceProfileSaveJob, Long> {
    Optional<VoiceProfileSaveJob> findByPublicIdAndUserId(String publicId, Long userId);
    Optional<VoiceProfileSaveJob> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
