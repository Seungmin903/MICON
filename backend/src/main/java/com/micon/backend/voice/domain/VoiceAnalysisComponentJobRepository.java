package com.micon.backend.voice.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface VoiceAnalysisComponentJobRepository extends JpaRepository<VoiceAnalysisComponentJob, Long> {
    List<VoiceAnalysisComponentJob> findBySessionIdOrderByComponentTypeAscAttemptNoDesc(Long sessionId);
    Optional<VoiceAnalysisComponentJob> findByAiJobId(String aiJobId);
    Optional<VoiceAnalysisComponentJob> findByRequestId(String requestId);
    Optional<VoiceAnalysisComponentJob> findFirstBySessionIdOrderByAttemptNoDesc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisComponentJob job join fetch job.session session "
            + "join fetch session.user where job.requestId = :requestId")
    Optional<VoiceAnalysisComponentJob> findByRequestIdForUpdate(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisComponentJob job join fetch job.session session "
            + "join fetch session.user where job.id = :jobId and session.user.id = :userId")
    Optional<VoiceAnalysisComponentJob> findOwnedByIdForUpdate(@Param("jobId") Long jobId,
                                                               @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisComponentJob job join fetch job.session "
            + "where job.status in ('PENDING', 'QUEUED', 'RUNNING') and job.createdAt <= :cutoff "
            + "order by job.createdAt")
    List<VoiceAnalysisComponentJob> findTimedOutForUpdate(@Param("cutoff") java.time.LocalDateTime cutoff);
}
