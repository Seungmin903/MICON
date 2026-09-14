package com.micon.backend.voice.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "voice_recording_segment")
public class VoiceRecordingSegment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recording_segment_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recording_id", nullable = false) private VoiceRecording recording;
    @Column(name = "sequence_no", nullable = false) private short sequenceNo;
    @Column(name = "recording_start_ms", nullable = false) private int recordingStartMs;
    @Column(name = "recording_end_ms", nullable = false) private int recordingEndMs;
    @Column(name = "song_start_ms", nullable = false) private int songStartMs;
    @Column(name = "song_end_ms", nullable = false) private int songEndMs;
    protected VoiceRecordingSegment() {}
}
