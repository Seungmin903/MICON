package com.micon.backend.domain.voice.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceModelResultRepository extends JpaRepository<VoiceModelResult, Long> {
    Optional<VoiceModelResult> findBySessionIdAndModelKey(Long sessionId, String modelKey);
}
