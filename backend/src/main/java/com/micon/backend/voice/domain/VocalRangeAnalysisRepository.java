package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface VocalRangeAnalysisRepository extends JpaRepository<VocalRangeAnalysis, Long> {
    Optional<VocalRangeAnalysis> findByVoiceAnalysisId(Long voiceAnalysisId);
}
