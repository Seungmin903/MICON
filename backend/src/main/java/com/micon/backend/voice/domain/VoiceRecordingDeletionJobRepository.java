package com.micon.backend.voice.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceRecordingDeletionJobRepository extends JpaRepository<VoiceRecordingDeletionJob, Long> {
    Optional<VoiceRecordingDeletionJob> findByRecordingId(Long recordingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from VoiceRecordingDeletionJob job join fetch job.recording where job.id = :id")
    Optional<VoiceRecordingDeletionJob> findByIdForUpdate(@Param("id") Long id);

    @Query("select job.id from VoiceRecordingDeletionJob job where "
            + "((job.status in ('PENDING', 'FAILED') and job.nextAttemptAt <= :now) "
            + "or (job.status = 'PROCESSING' and job.updatedAt <= :staleBefore)) order by job.id")
    List<Long> findDueIds(@Param("now") LocalDateTime now,
                          @Param("staleBefore") LocalDateTime staleBefore);
}
