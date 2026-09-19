package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.VoiceAnalysisComponentJob;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface VoiceAnalysisComponentJobRepository extends JpaRepository<VoiceAnalysisComponentJob, Long> {
    List<VoiceAnalysisComponentJob> findBySessionIdOrderByComponentTypeAscAttemptNoDesc(Long sessionId);
    Optional<VoiceAnalysisComponentJob> findByAiJobId(String aiJobId);
    Optional<VoiceAnalysisComponentJob> findByRequestId(String requestId);
    Optional<VoiceAnalysisComponentJob> findFirstBySessionIdOrderByAttemptNoDesc(Long sessionId);
    Optional<VoiceAnalysisComponentJob> findByAnalysisJobIdAndComponentType(Long analysisJobId,
                                                                            String componentType);
    List<VoiceAnalysisComponentJob> findByAnalysisJobId(Long analysisJobId);
    List<VoiceAnalysisComponentJob> findByAnalysisJobIdOrderByComponentTypeAsc(Long analysisJobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceAnalysisComponentJob job where job.analysisJob.id = :analysisJobId "
            + "order by job.componentType")
    List<VoiceAnalysisComponentJob> findByAnalysisJobIdForUpdate(@Param("analysisJobId") Long analysisJobId);

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
            + "where job.status in ('PENDING','DISPATCH_UNKNOWN','QUEUED','RUNNING') "
            + "and job.createdAt <= :cutoff order by job.createdAt")
    List<VoiceAnalysisComponentJob> findTimedOutForUpdate(@Param("cutoff") java.time.LocalDateTime cutoff);
}
