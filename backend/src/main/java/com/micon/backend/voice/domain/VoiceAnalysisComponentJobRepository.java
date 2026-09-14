package com.micon.backend.voice.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceAnalysisComponentJobRepository extends JpaRepository<VoiceAnalysisComponentJob, Long> {
    List<VoiceAnalysisComponentJob> findBySessionIdOrderByComponentTypeAscAttemptNoDesc(Long sessionId);
    Optional<VoiceAnalysisComponentJob> findByAiJobId(String aiJobId);
}
