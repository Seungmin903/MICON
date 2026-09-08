package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VoiceAnalysisRepository extends JpaRepository<VoiceAnalysis, Long> {

    Optional<VoiceAnalysis> findByJobId(String jobId);
}
