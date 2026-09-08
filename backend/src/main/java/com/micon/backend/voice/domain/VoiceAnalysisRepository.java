package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface VoiceAnalysisRepository extends JpaRepository<VoiceAnalysis, Long> {

    boolean existsByRecordingIdAndStatusIn(Long recordingId, Collection<VoiceAnalysisStatus> statuses);

    Optional<VoiceAnalysis> findByIdAndUserId(Long id, Long userId);
}
