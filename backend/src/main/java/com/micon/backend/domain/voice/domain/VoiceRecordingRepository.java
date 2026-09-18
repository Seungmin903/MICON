package com.micon.backend.domain.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VoiceRecordingRepository extends JpaRepository<VoiceRecording, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recording from VoiceRecording recording join fetch recording.user "
            + "where recording.user.id = :userId and recording.deletedAt is null order by recording.id")
    List<VoiceRecording> findActiveByUserIdForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recording from VoiceRecording recording join fetch recording.user where recording.id = :recordingId")
    Optional<VoiceRecording> findByIdForUpdate(@Param("recordingId") Long recordingId);

    Page<VoiceRecording> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Optional<VoiceRecording> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    long countByUserIdAndIdLessThanEqualAndDeletedAtIsNull(Long userId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recording from VoiceRecording recording join fetch recording.user "
            + "where recording.id = :recordingId and recording.user.id = :userId")
    Optional<VoiceRecording> findByIdAndUserIdForUpdate(@Param("recordingId") Long recordingId,
                                                        @Param("userId") Long userId);
}
