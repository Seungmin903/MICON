package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.VoiceRecordingSegment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRecordingSegmentRepository extends JpaRepository<VoiceRecordingSegment, Long> {
    List<VoiceRecordingSegment> findByRecordingIdOrderBySequenceNo(Long recordingId);
}
