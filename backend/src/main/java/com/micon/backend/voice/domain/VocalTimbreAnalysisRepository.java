package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface VocalTimbreAnalysisRepository extends JpaRepository<VocalTimbreAnalysis, Long> {
    Optional<VocalTimbreAnalysis> findByVoiceAnalysisId(Long voiceAnalysisId);
}
