package com.micon.backend.voice.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRecordingSegmentRepository extends JpaRepository<VoiceRecordingSegment, Long> {
    List<VoiceRecordingSegment> findByRecordingIdOrderBySequenceNo(Long recordingId);
}
