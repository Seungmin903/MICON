package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VoiceAnalysisRepository extends JpaRepository<VoiceAnalysis, Long> {

    boolean existsByRecordingIdAndStatusIn(Long recordingId, Collection<VoiceAnalysisStatus> statuses);

    Optional<VoiceAnalysis> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from VoiceAnalysis analysis where analysis.id = :id")
    Optional<VoiceAnalysis> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select analysis from VoiceAnalysis analysis where analysis.jobId = :jobId")
    Optional<VoiceAnalysis> findByJobIdForUpdate(@Param("jobId") String jobId);

    Page<VoiceAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<VoiceAnalysis> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
