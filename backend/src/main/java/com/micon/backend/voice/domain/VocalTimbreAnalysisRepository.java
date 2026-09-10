package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VocalTimbreAnalysisRepository extends JpaRepository<VocalTimbreAnalysis, Long> {
    Optional<VocalTimbreAnalysis> findByVoiceAnalysisId(Long voiceAnalysisId);

    @Modifying(flushAutomatically = true)
    @Query("delete from VocalTimbreAnalysis result where result.voiceAnalysis.id = :analysisId")
    int deleteByVoiceAnalysisId(@Param("analysisId") Long analysisId);
}
