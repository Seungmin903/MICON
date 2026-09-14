package com.micon.backend.voice.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceAnalysisSessionRepository extends JpaRepository<VoiceAnalysisSession, Long> {
    Optional<VoiceAnalysisSession> findByIdAndUserId(Long id, Long userId);
}
