package com.micon.backend.voice.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_range_measurement")
public class VoiceRangeMeasurement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "range_measurement_id") private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, unique = true) private VoiceAnalysisSession session;
    @Column(name = "measurement_uid", nullable = false, unique = true, length = 100) private String measurementUid;
    @Column(name = "rule_version", nullable = false, length = 50) private String ruleVersion;
    @Column(nullable = false, length = 30) private String source;
    @Column(name = "full_low_midi", precision = 6, scale = 2) private BigDecimal fullLowMidi;
    @Column(name = "full_high_midi", precision = 6, scale = 2) private BigDecimal fullHighMidi;
    @Column(name = "note_results", nullable = false, columnDefinition = "json") private String noteResults;
    @Column(name = "measurement_status", nullable = false, length = 30) private String measurementStatus;
    @Column(name = "quality_status", nullable = false, length = 30) private String qualityStatus;
    @Column(name = "measured_at", nullable = false) private LocalDateTime measuredAt;
    @Column(name = "submitted_at", nullable = false) private LocalDateTime submittedAt;
    protected VoiceRangeMeasurement() {}
    public VoiceRangeMeasurement(VoiceAnalysisSession session, String measurementUid, String ruleVersion,
                                 BigDecimal lowMidi, BigDecimal highMidi, String noteResults,
                                 String measurementStatus, String qualityStatus, LocalDateTime measuredAt) {
        this.session = session; this.measurementUid = measurementUid; this.ruleVersion = ruleVersion;
        this.source = "SERVER_AI"; this.fullLowMidi = lowMidi; this.fullHighMidi = highMidi;
        this.noteResults = noteResults; this.measurementStatus = measurementStatus;
        this.qualityStatus = qualityStatus; this.measuredAt = measuredAt; this.submittedAt = LocalDateTime.now();
    }
    public BigDecimal getFullLowMidi() { return fullLowMidi; }
    public BigDecimal getFullHighMidi() { return fullHighMidi; }
    public String getNoteResults() { return noteResults; }
    public String getMeasurementStatus() { return measurementStatus; }
    public String getQualityStatus() { return qualityStatus; }
}
