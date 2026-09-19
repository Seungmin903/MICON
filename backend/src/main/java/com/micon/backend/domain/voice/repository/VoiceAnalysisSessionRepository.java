package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;

import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceAnalysisSessionRepository extends JpaRepository<VoiceAnalysisSession, Long> {
    Optional<VoiceAnalysisSession> findByIdAndUserId(Long id, Long userId);
    Optional<VoiceAnalysisSession> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<String> statuses);
}
