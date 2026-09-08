package com.micon.backend.voice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRecordingRepository extends JpaRepository<VoiceRecording, Long> {
}
