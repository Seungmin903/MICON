package com.micon.backend.domain.voice.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRecordingContextRepository extends JpaRepository<VoiceRecordingContext, Long> {
    Optional<VoiceRecordingContext> findBySessionId(Long sessionId);
}
