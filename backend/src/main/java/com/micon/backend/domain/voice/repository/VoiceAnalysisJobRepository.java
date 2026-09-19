package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.VoiceAnalysisJob;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface VoiceAnalysisJobRepository extends JpaRepository<VoiceAnalysisJob, Long> {
    Optional<VoiceAnalysisJob> findByRequestId(String requestId);
    Optional<VoiceAnalysisJob> findFirstBySessionIdOrderByAttemptNoDesc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisJob job join fetch job.session session "
            + "join fetch session.user where job.requestId = :requestId")
    Optional<VoiceAnalysisJob> findByRequestIdForUpdate(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisJob job join fetch job.session session "
            + "join fetch session.user where job.id = :jobId and session.user.id = :userId")
    Optional<VoiceAnalysisJob> findOwnedForUpdate(@Param("jobId") Long jobId,
                                                   @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisJob job join fetch job.session "
            + "where job.status in ('PENDING','DISPATCH_UNKNOWN','QUEUED','RUNNING') "
            + "and job.createdAt <= :cutoff order by job.createdAt")
    List<VoiceAnalysisJob> findTimedOutForUpdate(@Param("cutoff") LocalDateTime cutoff);
}
