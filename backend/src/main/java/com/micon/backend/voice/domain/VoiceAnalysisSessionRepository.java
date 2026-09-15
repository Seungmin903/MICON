package com.micon.backend.voice.domain;

import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceAnalysisSessionRepository extends JpaRepository<VoiceAnalysisSession, Long> {
    Optional<VoiceAnalysisSession> findByIdAndUserId(Long id, Long userId);
    Optional<VoiceAnalysisSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<String> statuses);
}
