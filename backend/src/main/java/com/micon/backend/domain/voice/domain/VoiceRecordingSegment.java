package com.micon.backend.domain.voice.domain;

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
    public VoiceRecordingSegment(VoiceRecording recording, short sequenceNo, int recordingStartMs,
                                 int recordingEndMs, int songStartMs, int songEndMs) {
        this.recording = recording;
        this.sequenceNo = sequenceNo;
        this.recordingStartMs = recordingStartMs;
        this.recordingEndMs = recordingEndMs;
        this.songStartMs = songStartMs;
        this.songEndMs = songEndMs;
    }
}
