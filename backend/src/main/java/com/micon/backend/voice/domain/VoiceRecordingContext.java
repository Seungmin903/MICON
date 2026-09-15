package com.micon.backend.voice.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "voice_recording_context")
public class VoiceRecordingContext {
    @Id @Column(name = "recording_id") private Long recordingId;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @MapsId
    @JoinColumn(name = "recording_id") private VoiceRecording recording;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private VoiceAnalysisSession session;
    @Column(name = "song_id", nullable = false) private Long songId;
    @Column(name = "start_position_type", nullable = false, length = 30) private String startPositionType;
    @Column(name = "selected_start_ms", nullable = false) private int selectedStartMs;
    @Column(name = "actual_start_ms", nullable = false) private int actualStartMs;
    @Column(name = "accumulated_active_recording_ms", nullable = false) private int activeRecordingMs;
    @Column(name = "instrumental_version", nullable = false, length = 100) private String instrumentalVersion;
    @Column(name = "lyrics_version", nullable = false, length = 100) private String lyricsVersion;
    @Column(name = "upload_idempotency_key", nullable = false, length = 100) private String uploadIdempotencyKey;
    protected VoiceRecordingContext() {}
    public VoiceRecordingContext(VoiceRecording recording, VoiceAnalysisSession session, Long songId,
                                 String startPositionType, int selectedStartMs, int actualStartMs,
                                 int activeRecordingMs, String instrumentalVersion,
                                 String lyricsVersion, String uploadIdempotencyKey) {
        this.recording = recording;
        this.session = session;
        this.songId = songId;
        this.startPositionType = startPositionType;
        this.selectedStartMs = selectedStartMs;
        this.actualStartMs = actualStartMs;
        this.activeRecordingMs = activeRecordingMs;
        this.instrumentalVersion = instrumentalVersion;
        this.lyricsVersion = lyricsVersion;
        this.uploadIdempotencyKey = uploadIdempotencyKey;
    }
    public Long getRecordingId() { return recordingId; }
    public VoiceAnalysisSession getSession() { return session; }
}
